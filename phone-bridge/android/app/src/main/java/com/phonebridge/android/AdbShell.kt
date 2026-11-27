package com.phonebridge.android

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The built-in ADB path (the iTell way): the app pairs with its own phone over wireless debugging,
 * connects as the shell user, and starts CallTapService with `app_process` — the same shell rights
 * Shizuku gives, but with no second app. Android 12+ (ShellMain needs AttributionSource).
 *
 * It uses libadb-android for pairing and the ADB connection (ShellMain hands the binder back through
 * ShellBinderProvider). This is separate from the Shizuku path; CallTap uses whichever is ready.
 *
 * Results of [connect], as the `why` text: "not paired" (the phone does not know this app yet),
 * "no wireless debugging" (it is off, or there is no Wi-Fi), or the error itself.
 */
object AdbShell {
    private const val TAG = "PB-AdbShell"
    private const val PREFS = "pb"
    private const val KEY_TOKEN = "adb_token"
    private const val KEY_PAIRED = "adb_paired"

    const val NOT_PAIRED = "not paired"
    const val NO_WIRELESS = "no wireless debugging"

    @Volatile private var binder: ICallTap? = null
    @Volatile private var latch: CountDownLatch? = null
    @Volatile var state: String = "idle"; private set // idle | pairing | connecting | starting | ready | error
    @Volatile var detail: String = ""; private set

    fun ready(): Boolean = binder?.asBinder()?.pingBinder() == true
    fun service(): ICallTap? = binder?.takeIf { it.asBinder().pingBinder() }

    /** A stable random token so only the shell process we started may attach its binder. */
    fun token(ctx: Context?): String {
        val p = (ctx ?: return "").getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        p.edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    /** True once this app was paired with the phone's wireless debugging. */
    fun paired(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)

    private fun setPaired(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAIRED, v).apply()
    }

    /** Forget that pairing happened (the user wants to pair again). */
    fun forgetPairing(ctx: Context) { setPaired(ctx, false); state = "idle"; detail = "" }

    /** Clears an old error so the screens stop showing it. */
    fun clearError() { if (state == "error") { state = "idle"; detail = "" } }

    /** The arguments for `app_process` that start ShellMain as the shell user. The emulator test uses the same ones. */
    fun appProcessArgs(app: Context): String =
        "-Djava.class.path=${app.packageCodePath} /system/bin --nice-name=${app.packageName}:adb " +
            "${ShellMain::class.java.name} ${token(app)}"

    /** Called by ShellBinderProvider when the shell process hands us its binder. */
    fun onShellBinder(b: ICallTap) {
        binder = b
        try { b.asBinder().linkToDeath({ binder = null; if (state == "ready") state = "idle" }, 0) } catch (_: Throwable) {}
        state = "ready"
        latch?.countDown()
    }

    /** First-time pairing. Runs off the main thread; reports back through [onResult]. */
    fun pair(ctx: Context, port: Int, code: String, onResult: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        state = "pairing"; detail = ""
        thread(name = "pb-adb-pair") {
            try {
                AdbManager.getInstance(app).pair("127.0.0.1", port, code)
            } catch (e: Throwable) {
                Log.w(TAG, "pair", e)
                state = "error"; detail = "pairing failed"
                onResult(false, "pairing failed: " + short(e))
                return@thread
            }
            setPaired(app, true)
            state = "idle"
            onResult(true, "")
        }
    }

    /** Connect (auto-finds the wireless-debugging port) and start the call-line service. */
    fun connect(ctx: Context, onResult: (Boolean, String) -> Unit) {
        if (ready()) { onResult(true, ""); return }
        val app = ctx.applicationContext
        state = "connecting"; detail = ""
        thread(name = "pb-adb-connect") {
            val mgr = try { AdbManager.getInstance(app) } catch (e: Throwable) {
                fail(onResult, short(e)); return@thread
            }
            try {
                if (!mgr.autoConnect(app, 12_000)) { fail(onResult, "the phone refused the connection"); return@thread }
            } catch (e: Throwable) {
                Log.w(TAG, "autoConnect", e)
                val names = generateSequence(e) { it.cause }.take(4).map { it.javaClass.simpleName }.toList()
                val why = when {
                    "AdbPairingRequiredException" in names -> NOT_PAIRED
                    e is InterruptedException || (e.message ?: "").contains("valid host address") -> NO_WIRELESS
                    else -> short(e)
                }
                fail(onResult, why)
                return@thread
            }

            state = "starting"
            val l = CountDownLatch(1); latch = l
            try {
                val cmd = "nohup app_process ${appProcessArgs(app)} >/dev/null 2>&1 &"
                val stream = mgr.openStream("shell:$cmd")
                // Drain briefly for any immediate error, then leave the nohup'd process running.
                thread(name = "pb-adb-drain", isDaemon = true) {
                    try { stream.openInputStream().use { it.readBytes() } } catch (_: Throwable) {}
                }
            } catch (e: Throwable) { latch = null; fail(onResult, short(e)); return@thread }

            val got = try { l.await(15, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
            latch = null
            try { mgr.close() } catch (_: Throwable) {} // the nohup process keeps running
            if (got && ready()) { state = "ready"; onResult(true, "") }
            else fail(onResult, "the call line service did not start")
        }
    }

    fun stop() {
        try { binder?.destroy() } catch (_: Throwable) {}
        binder = null
        state = "idle"
    }

    private fun fail(onResult: (Boolean, String) -> Unit, why: String) {
        state = "error"; detail = why
        onResult(false, why)
    }

    private fun short(e: Throwable): String =
        ((e.cause ?: e).javaClass.simpleName + ": " + ((e.cause ?: e).message ?: "")).take(120)
}
