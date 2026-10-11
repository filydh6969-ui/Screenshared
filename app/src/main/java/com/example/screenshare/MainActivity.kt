package com.example.screenshare

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var btn: Button
    private lateinit var dim: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        info = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER }
        dim = CheckBox(this).apply { text = "Mantener pantalla encendida y atenuada (la laptop sigue viendo)"; isChecked = true }
        val bAcc = Button(this).apply {
            text = "1. Activar control (Accesibilidad)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val bDim = Button(this).apply {
            text = "2. Permitir atenuar brillo"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        btn = Button(this)
        listOf(info, dim, bAcc, bDim, btn).forEach { root.addView(it) }
        setContentView(root)

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(perms.toTypedArray(), 1)

        btn.setOnClickListener {
            if (ScreenService.running) {
                startService(Intent(this, ScreenService::class.java).setAction("STOP"))
                btn.postDelayed({ refresh() }, 500)
            } else {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), 100)
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            ScreenService.key = (100000..999999).random().toString()
            val i = Intent(this, ScreenService::class.java)
                .putExtra("code", resultCode).putExtra("data", data)
                .putExtra("key", ScreenService.key).putExtra("dim", dim.isChecked)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            btn.postDelayed({ refresh() }, 700)
        }
    }

    private fun refresh() {
        val p = ScreenService.PORT
        val k = ScreenService.key
        val ctl = if (ControlService.instance != null) "Control: activo" else "Control: inactivo"
        if (ScreenService.running) {
            btn.text = "Detener"
            info.text = "Transmitiendo ($ctl)\n\nWiFi (misma red):\nhttp://${localIp()}:$p/?k=$k\n\n" +
                "USB (en la laptop):\nadb forward tcp:$p tcp:$p\nhttp://localhost:$p/?k=$k"
        } else {
            btn.text = "3. Iniciar transmisión"
            info.text = "$ctl\nPulsa iniciar para compartir la pantalla"
        }
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "IP-DEL-CEL"
    } catch (e: Exception) { "IP-DEL-CEL" }
}
