package com.example.screenshare

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class ControlService : AccessibilityService() {
    companion object { @Volatile var instance: ControlService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Toque, deslizamiento o pulsación larga (mismo punto + duración larga). */
    fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1))).build()
        dispatchGesture(g, null, null)
    }
}
