package com.phonebridge.android

import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process

/**
 * Makes the shell process look like a normal app process to the audio framework, the same way iTell
 * and scrcpy do it: an ActivityThread, the system Context, and a Context that says it is
 * "com.android.shell". Without this, Android checks "our package under uid 2000" and blocks the call
 * line (seen in the emulator test).
 */
object ShellEnv {
    const val SHELL = "com.android.shell"

    @Volatile private var made: Context? = null

    @Synchronized
    fun context(fallback: Context?): Context {
        made?.let { return it }
        val base = systemContext() ?: fallback ?: error("no Context in the shell process")
        val shell = ShellContext(base)
        attachApplication(shell)
        made = shell
        return shell
    }

    private fun systemContext(): Context? = try {
        val cls = Class.forName("android.app.ActivityThread")
        var thread = cls.getMethod("currentActivityThread").invoke(null)
        if (thread == null) {
            try { Looper.prepareMainLooper() } catch (_: Throwable) {} // already prepared is fine
            thread = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            cls.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            cls.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            try {
                val internal = Class.forName("android.app.ActivityThreadInternal")
                val controller = Class.forName("android.app.ConfigurationController")
                    .getDeclaredConstructor(internal).apply { isAccessible = true }.newInstance(thread)
                cls.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
            } catch (_: Throwable) {}
        }
        cls.getDeclaredMethod("getSystemContext").invoke(thread) as Context
    } catch (_: Throwable) { null }

    /** The audio framework reads ActivityThread.currentApplication(); give it one with the shell identity. */
    private fun attachApplication(shell: Context) {
        try {
            val cls = Class.forName("android.app.ActivityThread")
            val thread = cls.getMethod("currentActivityThread").invoke(null) ?: return
            val field = cls.getDeclaredField("mInitialApplication").apply { isAccessible = true }
            if (field.get(thread) != null) return
            val app = Application()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(app, shell)
            field.set(thread, app)
        } catch (_: Throwable) {}
    }

    /** "I am com.android.shell" (the uid is already 2000). */
    class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = SHELL
        override fun getOpPackageName(): String = SHELL
        override fun getAttributionTag(): String? = null

        @androidx.annotation.RequiresApi(31)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()

        /** System services keep the Context they were made with; point them at this one. */
        override fun getSystemService(name: String): Any? {
            val service = super.getSystemService(name) ?: return null
            var cls: Class<*>? = service.javaClass
            while (cls != null && cls != Any::class.java) {
                val f = try { cls.getDeclaredField("mContext") } catch (_: Throwable) { null }
                if (f != null && Context::class.java.isAssignableFrom(f.type)) {
                    try { f.isAccessible = true; f.set(service, this) } catch (_: Throwable) {}
                    break
                }
                cls = cls.superclass
            }
            return service
        }
    }
}
