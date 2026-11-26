package com.phonebridge.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing by itself. Android 10+ lets an app record during a phone call only while one of its
 * accessibility services is on, so this service exists to switch that on. It reads no screen content.
 */
class CallAudioAccess : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        fun enabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
