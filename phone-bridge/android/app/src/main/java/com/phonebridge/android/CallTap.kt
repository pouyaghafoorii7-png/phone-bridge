package com.phonebridge.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The app side of the direct call line (the iTell method). The call-line service runs with the shell
 * identity either through the app's own built-in ADB over wireless debugging (AdbShell, no second
 * app) or through Shizuku. CallTap uses whichever is ready. Without either, AudioBridge falls back
 * to the microphone.
 */
object CallTap {
    private const val TAG = "PB-CallTap"
    const val REQUEST = 4721

    @Volatile private var service: ICallTap? = null // the Shizuku-bound service (ADB is in AdbShell)
    private var bound = false
    private var appCtx: Context? = null

    /** The live service, from built-in ADB first, else Shizuku. */
    private fun svc(): ICallTap? =
        AdbShell.service() ?: service?.takeIf { it.asBinder().pingBinder() }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, CallTapService::class.java.name))
            .daemon(false)
            .processNameSuffix("calltap")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) ICallTap.Stub.asInterface(binder) else null
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val onGranted = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST && result == PackageManager.PERMISSION_GRANTED) appCtx?.let { prepare(it) }
    }
    private val onBinder = Shizuku.OnBinderReceivedListener { appCtx?.let { prepare(it) } }
    private val onDead = Shizuku.OnBinderDeadListener { service = null; bound = false }

    private var listening = false

    /** Shizuku is running (started with ADB or root). */
    fun running(): Boolean = try { Shizuku.pingBinder() && !Shizuku.isPreV11() } catch (_: Throwable) { false }

    fun granted(): Boolean = running() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun ready(): Boolean = svc() != null

    /** The uid the call line service runs with (2000 = shell), or -1. */
    fun serviceUid(): Int = try { svc()?.uid() ?: -1 } catch (_: Exception) { -1 }

    /** How the call line is set up, for the screens. */
    fun transport(): String = when {
        AdbShell.ready() -> "adb"
        service != null -> "shizuku"
        else -> ""
    }

    /** One word for the screens: "missing", "denied", "starting" or "ready". */
    fun state(): String = when {
        svc() != null -> "ready"
        AdbShell.state == "connecting" || AdbShell.state == "starting" || AdbShell.state == "pairing" -> "starting"
        running() && !granted() -> "denied"
        running() -> "starting"
        AdbShell.state.startsWith("error") -> "denied"
        else -> "missing"
    }

    /** Shows the Shizuku permission dialog. */
    fun requestPermission(ctx: Context) {
        listen(ctx)
        if (running() && !granted()) try { Shizuku.requestPermission(REQUEST) } catch (e: Throwable) { Log.w(TAG, e) }
    }

    /** Starts the shell service early, so it is ready when a call starts. Safe to call many times. */
    fun prepare(ctx: Context) {
        listen(ctx)
        if (bound || !granted()) return
        try {
            Shizuku.bindUserService(args, conn)
            bound = true
        } catch (e: Throwable) {
            Log.w(TAG, "bind failed", e)
        }
    }

    private fun listen(ctx: Context) {
        appCtx = ctx.applicationContext
        if (listening) return
        listening = true
        Shizuku.addRequestPermissionResultListener(onGranted)
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
    }

    /** The service's methods for this phone, best first (empty when the service is not there). */
    fun methods(): List<String> = try {
        svc()?.methods()?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** One line about the phone (maker, Android, chip, what the call line offers). */
    fun info(): String = try { svc()?.info() ?: "" } catch (_: Exception) { "" }

    /** The service's own recent log lines, for bug reports. */
    fun log(): String = try { svc()?.log() ?: "" } catch (_: Exception) { "" }

    /**
     * Opens the call line with one method: the caller's voice comes out of [CallLine.read], the
     * iPhone voice goes in with [CallLine.send]. Returns null and the reason when the phone refuses.
     */
    fun openLine(method: String): Pair<CallLine?, String> {
        val s = svc() ?: return null to state()
        val down = ParcelFileDescriptor.createPipe()
        val up = ParcelFileDescriptor.createPipe()
        val why = try {
            s.open(method, AudioBridge.RATE, down[1], up[0])
        } catch (e: Exception) { e.message ?: "binder" }
        down[1].close() // the shell process has its own copies now
        up[0].close()
        if (why.isNotEmpty()) {
            down[0].close()
            up[1].close()
            Log.w(TAG, "open $method: $why")
            return null to why
        }
        val route = try { s.route() } catch (_: Exception) { "direct" }
        return CallLine(s, method, route, down[0], up[1]) to ""
    }

    /** Both directions of the call through the shell service. */
    class CallLine(
        private val s: ICallTap,
        val method: String,
        /** "direct": the iPhone voice goes into the call. "speaker": the service plays it on the speaker. */
        val route: String,
        downFd: ParcelFileDescriptor,
        upFd: ParcelFileDescriptor,
    ) : PcmSource {
        private val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(downFd))
        private val output = ParcelFileDescriptor.AutoCloseOutputStream(upFd)
        private val inBytes = ByteArray(AudioBridge.FRAME * 2)
        private val outBytes = ByteArray(AudioBridge.FRAME * 2)
        @Volatile private var upBroken = false

        override val name = (if (route == "direct") "call line (" else "speaker bridge (") + LineMethods.label(method) + ")"
        override val direct = route == "direct"

        override fun read(buf: ShortArray): Boolean = try {
            input.readFully(inBytes)
            ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buf)
            true
        } catch (_: IOException) { false }

        /** One 40 ms frame of the iPhone user's voice into the call. */
        fun send(frame: ShortArray) {
            if (upBroken) return
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(frame, 0, AudioBridge.FRAME)
            try { output.write(outBytes) } catch (_: IOException) { upBroken = true }
        }

        override fun close() {
            try { s.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }
}

/** One frame-based audio input: the direct call line or a microphone. */
interface PcmSource {
    val name: String
    /** True for the call line from Shizuku. */
    val direct: Boolean
    /** Fills the whole buffer. False when the source stopped. */
    fun read(buf: ShortArray): Boolean
    fun close()
}
