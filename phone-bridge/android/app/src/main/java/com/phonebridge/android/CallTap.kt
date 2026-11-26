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
 * The app side of the direct call line (the iTell method). Shizuku runs CallTapService with the shell
 * identity; Shizuku itself is started one time with ADB (USB or wireless debugging) or root.
 * Without Shizuku, AudioBridge uses the microphone as before.
 */
object CallTap {
    private const val TAG = "PB-CallTap"
    const val REQUEST = 4721

    @Volatile private var service: ICallTap? = null
    private var bound = false
    private var appCtx: Context? = null

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

    fun ready(): Boolean = service != null

    /** The uid the call line service runs with (2000 = shell), or -1. */
    fun serviceUid(): Int = try { service?.uid() ?: -1 } catch (_: Exception) { -1 }

    /** One word for the screens: "missing", "denied", "starting" or "ready". */
    fun state(): String = when {
        !running() -> "missing"
        !granted() -> "denied"
        service == null -> "starting"
        else -> "ready"
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
        service?.methods()?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** One line about the phone (maker, Android, chip, what the call line offers). */
    fun info(): String = try { service?.info() ?: "" } catch (_: Exception) { "" }

    /**
     * Opens the call line with one method: the caller's voice comes out of [CallLine.read], the
     * iPhone voice goes in with [CallLine.send]. Returns null and the reason when the phone refuses.
     */
    fun openLine(method: String): Pair<CallLine?, String> {
        val s = service ?: return null to state()
        val down = ParcelFileDescriptor.createPipe()
        val up = if (CallTapService.carriesUplink(method)) ParcelFileDescriptor.createPipe() else null
        val why = try {
            s.open(method, AudioBridge.RATE, down[1], up?.get(0))
        } catch (e: Exception) { e.message ?: "binder" }
        down[1].close() // the shell process has its own copies now
        up?.get(0)?.close()
        if (why.isNotEmpty()) {
            down[0].close()
            up?.get(1)?.close()
            Log.w(TAG, "open $method: $why")
            return null to why
        }
        return CallLine(s, method, down[0], up?.get(1)) to ""
    }

    /** Both directions of the call through the shell service. */
    class CallLine(
        private val s: ICallTap,
        val method: String,
        downFd: ParcelFileDescriptor,
        upFd: ParcelFileDescriptor?,
    ) : PcmSource {
        private val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(downFd))
        private val output = upFd?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
        private val inBytes = ByteArray(AudioBridge.FRAME * 2)
        private val outBytes = ByteArray(AudioBridge.FRAME * 2)
        @Volatile private var upBroken = false

        override val name = "call line ($method)"
        override val direct = true
        /** True when the iPhone voice goes straight into the call (no speaker needed). */
        val carriesUplink get() = output != null && !upBroken

        override fun read(buf: ShortArray): Boolean = try {
            input.readFully(inBytes)
            ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buf)
            true
        } catch (_: IOException) { false }

        /** One 40 ms frame of the iPhone user's voice into the call. */
        fun send(frame: ShortArray) {
            val out = output ?: return
            if (upBroken) return
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(frame, 0, AudioBridge.FRAME)
            try { out.write(outBytes) } catch (_: IOException) { upBroken = true }
        }

        override fun close() {
            try { s.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
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
