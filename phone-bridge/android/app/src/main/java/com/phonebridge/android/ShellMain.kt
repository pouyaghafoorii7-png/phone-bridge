package com.phonebridge.android

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * The entry point the ADB shell starts with `app_process` (built-in ADB path, the iTell way):
 *
 *   CLASSPATH=<our apk> app_process /system/bin --nice-name=... com.phonebridge.android.ShellMain <token>
 *
 * It runs with the shell identity (uid 2000), the same rights Shizuku would give. It builds a Context,
 * creates the call-line service and hands its binder to the app through ShellBinderProvider, then waits.
 *
 * Android 11+ only (the AttributionSource `call` shape and wireless-debugging pairing). On Android 10
 * the app uses Shizuku instead.
 */
object ShellMain {
    private const val TAG = "PB-ShellMain"
    private const val PKG = BuildConfig.APPLICATION_ID
    private val AUTHORITY = "$PKG.shell"

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0) ?: ""
        if (token.length < 16) {
            Log.e(TAG, "no token, exiting")
            exitProcess(1)
        }
        Log.i(TAG, "shell service starting, uid=${Process.myUid()}")
        Looper.prepareMainLooper()

        val ctx = ShellEnv.context(null)
        val service = CallTapService(ctx)

        val bundle = Bundle().apply {
            putString("token", token)
            putBinder("binder", service.asBinder())
        }
        val ok = try { attach(bundle) } catch (e: Throwable) { Log.e(TAG, "attach failed", e); null }
        if (ok == null) {
            Log.e(TAG, "could not hand the binder to the app")
            exitProcess(1)
        }
        Log.i(TAG, "binder handed to the app")
        Looper.loop()
    }

    /**
     * Calls ShellBinderProvider.call("attach", ...) in the app process. A bare app_process has no
     * ContentResolver, so we fetch the provider through ActivityManager, the way Shizuku does.
     */
    private fun attach(bundle: Bundle): Bundle? {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = am.javaClass
            .getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(am, AUTHORITY, 0, token, null) ?: return null
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            val call = provider.javaClass.methods.firstOrNull {
                it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
            } ?: error("no AttributionSource call() — needs Android 11+")
            val src = AttributionSource.Builder(Process.myUid()).setPackageName(ShellEnv.SHELL).build()
            return call.invoke(provider, src, AUTHORITY, "attach", null, bundle) as? Bundle
        } finally {
            try {
                am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(am, AUTHORITY, token, 0)
            } catch (_: Throwable) {}
        }
    }
}
