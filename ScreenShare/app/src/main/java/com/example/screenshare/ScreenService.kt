package com.example.screenshare

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ScreenService : Service() {
    companion object {
        const val PORT = 8080
        @Volatile var running = false
        @Volatile var key = ""
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var server: ServerSocket? = null
    private var worker: HandlerThread? = null
    private var audioRec: AudioRecord? = null
    private val lock = java.lang.Object()
    @Volatile private var frame: ByteArray? = null
    @Volatile private var frameId = 0L
    private var bmp: Bitmap? = null
    private var lastTs = 0L
    private val audioClients = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()

    private var cpuLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var oldBright = -1
    private var oldMode = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        key = intent.getStringExtra("key") ?: ""

        startAsForeground()
        keepAlive(intent.getBooleanExtra("dim", false))

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        worker = HandlerThread("capture").also { it.start() }
        val handler = Handler(worker!!.looper)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val m = resources.displayMetrics
        val scale = minOf(1f, 720f / m.widthPixels)
        val w = ((m.widthPixels * scale).toInt() / 2) * 2
        val h = ((m.heightPixels * scale).toInt() / 2) * 2

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastTs < 40) return@setOnImageAvailableListener
                lastTs = now
                val p = img.planes[0]
                val bw = p.rowStride / p.pixelStride
                if (bmp == null || bmp!!.width != bw) bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
                bmp!!.copyPixelsFromBuffer(p.buffer)
                val out = ByteArrayOutputStream()
                Bitmap.createBitmap(bmp!!, 0, 0, w, h).compress(Bitmap.CompressFormat.JPEG, 60, out)
                synchronized(lock) { frame = out.toByteArray(); frameId++; lock.notifyAll() }
            } finally { img.close() }
        }, handler)

        display = projection!!.createVirtualDisplay(
            "share", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )

        running = true
        startAudio()
        startServer()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("share", "Transmisión", NotificationManager.IMPORTANCE_LOW))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "share") else Notification.Builder(this))
            .setContentTitle("Compartiendo pantalla")
            .setSmallIcon(android.R.drawable.ic_menu_camera).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    /** Mantiene CPU y WiFi activos; opcionalmente pantalla encendida y con brillo mínimo. */
    @Suppress("DEPRECATION")
    private fun keepAlive(dim: Boolean) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ss:cpu").apply { acquire() }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ss:wifi").apply { acquire() }
        if (dim) {
            screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ss:screen").apply { acquire() }
            if (Settings.System.canWrite(this)) {
                val cr = contentResolver
                oldMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                oldBright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0)
            }
        }
    }

    /** Captura el audio interno del celular (Android 10+). */
    private fun startAudio() {
        if (Build.VERSION.SDK_INT < 29) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder().setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min * 2, 16384)).setAudioPlaybackCaptureConfig(cfg).build()
            audioRec = rec
            rec.startRecording()
            thread {
                val buf = ByteArray(7680) // ~40 ms
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val chunk = buf.copyOf(n)
                        for (q in audioClients) if (q.size < 12) q.offer(chunk)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun startServer() {
        thread {
            try {
                server = ServerSocket(PORT)
                while (running) {
                    val s = server!!.accept()
                    thread { handle(s) }
                }
            } catch (_: IOException) {}
        }
    }

    private fun reply(out: BufferedOutputStream, code: String, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun handle(s: Socket) {
        try {
            s.use {
                val rd = BufferedReader(InputStreamReader(it.getInputStream()))
                val line = rd.readLine() ?: return
                while (true) { val h = rd.readLine(); if (h == null || h.isEmpty()) break }
                val target = line.split(" ").getOrNull(1) ?: return
                val path = target.substringBefore("?")
                val q = target.substringAfter("?", "").split("&").filter { p -> p.contains("=") }
                    .associate { p -> p.substringBefore("=") to p.substringAfter("=") }
                val out = BufferedOutputStream(it.getOutputStream())

                if (q["k"] != key) { reply(out, "403 Forbidden", "text/plain", "clave incorrecta".toByteArray()); return }

                when (path) {
                    "/stream" -> {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
                        var last = -1L
                        while (running) {
                            var f: ByteArray?
                            synchronized(lock) {
                                while (frameId == last && running) lock.wait(1000)
                                f = frame; last = frameId
                            }
                            val bytes = f ?: continue
                            out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                            out.write(bytes); out.write("\r\n".toByteArray()); out.flush()
                        }
                    }
                    "/audio" -> {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        val qu = LinkedBlockingQueue<ByteArray>()
                        audioClients.add(qu)
                        try {
                            while (running) {
                                val c = qu.poll(1, TimeUnit.SECONDS) ?: continue
                                out.write(c); out.flush()
                            }
                        } finally { audioClients.remove(qu) }
                    }
                    "/ctl" -> { control(q); reply(out, "200 OK", "text/plain", "ok".toByteArray()) }
                    else -> reply(out, "200 OK", "text/html; charset=utf-8", assets.open("index.html").readBytes())
                }
            }
        } catch (_: Exception) {}
    }

    private fun control(q: Map<String, String>) {
        val m = resources.displayMetrics
        val W = m.widthPixels; val H = m.heightPixels
        fun f(n: String) = (q[n]?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
        val cs = ControlService.instance
        when (q["t"]) {
            "tap" -> cs?.gesture(f("x") * W, f("y") * H, f("x") * W, f("y") * H, 50)
            "swipe" -> cs?.gesture(f("x") * W, f("y") * H, f("x2") * W, f("y2") * H, q["d"]?.toLongOrNull() ?: 300)
            "back" -> cs?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> cs?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> cs?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "mute" -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (q["v"] == "1") AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            }
        }
    }

    override fun onDestroy() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        try { server?.close() } catch (_: IOException) {}
        try { audioRec?.stop(); audioRec?.release() } catch (_: Exception) {}
        display?.release()
        reader?.close()
        projection?.stop()
        worker?.quitSafely()
        try {
            (getSystemService(AUDIO_SERVICE) as AudioManager)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            if (oldBright >= 0 && Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, oldBright)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, oldMode)
            }
        } catch (_: Exception) {}
        screenLock?.takeIf { it.isHeld }?.release()
        cpuLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
