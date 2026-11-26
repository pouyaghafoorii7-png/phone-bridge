package com.phonebridge.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The call line, the iTell way. Shizuku starts this class in its own process with the shell identity
 * (uid 2000, the rights of `adb shell`). From there it can:
 *  - take the caller's voice out of the call (VOICE_DOWNLINK), and
 *  - put the iPhone user's voice into the call (the telephony output, call redirection, or the
 *    phone maker's own path),
 * so the call needs no speaker and no microphone.
 *
 * Phones differ, so there are several methods (see [order]). The app tries them in order and
 * remembers the one that works. It records nothing until the app calls [open].
 */
class CallTapService() : ICallTap.Stub() {
    private var given: Context? = null

    /** Shizuku 13+ gives a Context when the class has this constructor. */
    constructor(context: Context) : this() { given = context }

    private val shell: Context by lazy { ShellEnv.context(given) }
    private val audio: AudioManager by lazy { shell.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    /** The iPhone voice is 16 kHz; some telephony outputs take only 48 kHz: repeat each sample. */
    private var upFactor = 1
    private val pumps = mutableListOf<Thread>()
    private var modeBefore: Int? = null
    private var incallMusic = false
    private var mediaVolumeBefore: Int? = null

    // ---- AIDL ----

    override fun uid(): Int = Process.myUid()

    override fun info(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), " +
            "chip ${chip()}, telephony output ${telephonyOut() != null}, call interception ${pstnInterceptable()}"

    override fun methods(): String = order().joinToString(",")

    @Synchronized
    override fun open(method: String, rate: Int, down: ParcelFileDescriptor, up: ParcelFileDescriptor?): String {
        stopLocked()
        val out = ParcelFileDescriptor.AutoCloseOutputStream(down)
        val inp = up?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        try {
            when (method) {
                REDIRECT -> openRedirect(rate)
                TELEPHONY -> openTelephony(rate)
                SAMSUNG_TX -> openSamsungTx(rate)
                QCOM_INCALL -> openQcomIncall(rate)
                DOWNLINK -> record = downlink(rate)
                else -> error("unknown method $method")
            }
            val r = record ?: error("no capture")
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("capture blocked by the system")
            track?.play()
        } catch (e: Throwable) {
            stopLocked()
            closeQuietly(out); inp?.let { closeQuietly(it) }
            val why = (e.cause ?: e).let { (it.javaClass.simpleName + ": " + (it.message ?: "")).take(160) }
            Log.w(TAG, "open $method: $why")
            return why
        }
        running = true
        val r = record!!
        pumps += thread(name = "pb-down") { pumpDown(r, out) }
        val t = track
        if (t != null && inp != null) pumps += thread(name = "pb-up") { pumpUp(t, inp) } else inp?.let { closeQuietly(it) }
        Log.i(TAG, "call line open: $method at $rate Hz" + (if (upFactor > 1) ", up x$upFactor" else ""))
        return ""
    }

    @Synchronized
    override fun close() = stopLocked()

    override fun destroy() {
        close()
        exitProcess(0)
    }

    // ---- which methods, in which order (iTell's choice per phone) ----

    private fun order(): List<String> {
        val sdk = Build.VERSION.SDK_INT
        val maker = Build.MANUFACTURER.lowercase()
        val samsung = maker == "samsung"
        val list = mutableListOf<String>()
        // Android 13+ call redirection: Pixel, Motorola, and Samsung from Android 15.
        if (sdk >= 33 && (maker == "google" || maker == "motorola" || (samsung && sdk >= 35))) list += REDIRECT
        // The telephony output device: most phones, and Samsung up to Android 14.
        list += TELEPHONY
        if (samsung) list += SAMSUNG_TX
        if (sdk >= 33 && REDIRECT !in list) list += REDIRECT
        if (chip() == "qualcomm") list += QCOM_INCALL
        // Last: only the caller's voice; the iPhone voice then goes by speaker or cable.
        list += DOWNLINK
        return list
    }

    // ---- the methods ----

    /** Android 13+ API made for this (call assistants): both directions in one go. */
    private fun openRedirect(rate: Int) {
        check(Build.VERSION.SDK_INT >= 33) { "needs Android 13" }
        check(pstnInterceptable() != false) { "this phone does not allow call interception" }
        val pstn = try { AudioManager::class.java.getField("CALL_REDIRECT_PSTN").getInt(null) } catch (_: Throwable) { 1 }
        modeBefore = audio.mode
        try { audio.mode = MODE_CALL_REDIRECT } catch (e: Throwable) { Log.w(TAG, "MODE_CALL_REDIRECT: $e") }

        val capture = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(capture, MediaRecorder.AudioSource.VOICE_DOWNLINK)
        val rb = AudioRecord.Builder().setAudioFormat(pcmIn(rate)).setBufferSizeInBytes(bufIn(rate))
        withShell(rb)
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(rb, capture.build())
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType).invoke(rb, pstn)
        record = rb.build().also { check(it.state == AudioRecord.STATE_INITIALIZED) { "redirect capture not initialized" } }

        val play = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioAttributes.Builder::class.java.getMethod("setSystemUsage", Int::class.javaPrimitiveType).invoke(play, USAGE_CALL_ASSISTANT)
        val tb = AudioTrack.Builder().setAudioAttributes(play.build()).setAudioFormat(pcmOut(rate))
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufOut(rate))
        withShell(tb)
        AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType).invoke(tb, pstn)
        track = tb.build().also { check(it.state == AudioTrack.STATE_INITIALIZED) { "redirect track not initialized" } }
    }

    /** The caller from VOICE_DOWNLINK; our voice into the TELEPHONY output device (the call's uplink). */
    private fun openTelephony(rate: Int) {
        record = downlink(rate)
        val dev = telephonyOut() ?: error("no telephony output on this phone")
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        track = pinnedTrack(rate, attrs, dev) ?: error("the telephony output refused our track")
    }

    /** Samsung's own path: a track tagged VOICE_TX, pinned to the telephony output when it allows. */
    private fun openSamsungTx(rate: Int) {
        record = downlink(rate)
        val b = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioAttributes.Builder::class.java.getMethod("semAddAudioTag", String::class.java).invoke(b, "VOICE_TX")
        val attrs = b.build()
        val dev = telephonyOut()
        track = (dev?.let { pinnedTrack(rate, attrs, it) })
            ?: plainTrack(rate, attrs).also { upFactor = 1 }
            ?: error("VOICE_TX track not initialized")
    }

    /** Qualcomm: the HAL mixes "in-call music" into the call's uplink. */
    private fun openQcomIncall(rate: Int) {
        record = downlink(rate)
        halParams("incall_music_enabled=true")
        incallMusic = true
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        track = plainTrack(rate, attrs) ?: error("in-call music track not initialized")
        try {
            mediaVolumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 8 / 10, 0)
        } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun downlink(rate: Int): AudioRecord {
        val b = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            .setAudioFormat(pcmIn(rate)).setBufferSizeInBytes(bufIn(rate))
        withShell(b)
        return b.build().also { check(it.state == AudioRecord.STATE_INITIALIZED) { "VOICE_DOWNLINK not initialized" } }
    }

    /** A track pinned to `dev`: first at our rate, then at a rate the device lists (we repeat samples). */
    private fun pinnedTrack(rate: Int, attrs: AudioAttributes, dev: AudioDeviceInfo): AudioTrack? {
        val factors = mutableListOf(1)
        dev.sampleRates.filter { it > rate && it % rate == 0 }.map { it / rate }.sorted().forEach { if (it !in factors) factors += it }
        if (3 !in factors) factors += 3 // 48 kHz, the usual one when the device lists nothing
        for (f in factors) {
            val t = plainTrack(rate * f, attrs) ?: continue
            if (t.setPreferredDevice(dev)) { upFactor = f; return t }
            t.release()
        }
        return null
    }

    private fun plainTrack(rate: Int, attrs: AudioAttributes): AudioTrack? = try {
        val b = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(pcmOut(rate))
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufOut(rate))
        withShell(b)
        b.build().takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: run { null }
    } catch (_: Throwable) { null }

    // ---- pumps ----

    private fun pumpDown(r: AudioRecord, out: OutputStream) {
        val buf = ByteArray(AudioBridge.FRAME * 2)
        try {
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } catch (_: IOException) {
            // The app closed its end.
        } finally {
            closeQuietly(out)
        }
    }

    private fun pumpUp(t: AudioTrack, inp: InputStream) {
        val din = DataInputStream(inp)
        val frame = ByteArray(AudioBridge.FRAME * 2)
        val f = upFactor
        val wide = ByteArray(frame.size * f)
        try {
            while (running) {
                din.readFully(frame)
                if (f == 1) {
                    t.write(frame, 0, frame.size)
                } else {
                    var o = 0
                    var i = 0
                    while (i < frame.size) {
                        repeat(f) { wide[o++] = frame[i]; wide[o++] = frame[i + 1] }
                        i += 2
                    }
                    t.write(wide, 0, wide.size)
                }
            }
        } catch (_: IOException) {
            // The app closed its end, or the call ended.
        } finally {
            closeQuietly(inp)
        }
    }

    // ---- stop and put things back ----

    private fun stopLocked() {
        running = false
        record?.let { try { it.stop() } catch (_: Throwable) {}; it.release() }
        track?.let { try { it.pause(); it.flush(); it.stop() } catch (_: Throwable) {}; it.release() }
        record = null
        track = null
        pumps.forEach { it.join(500) }
        pumps.clear()
        upFactor = 1
        if (incallMusic) { halParams("incall_music_enabled=false"); incallMusic = false }
        mediaVolumeBefore?.let { try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) } catch (_: Throwable) {} }
        mediaVolumeBefore = null
        modeBefore?.let { try { if (audio.mode == MODE_CALL_REDIRECT) audio.mode = it } catch (_: Throwable) {} }
        modeBefore = null
    }

    // ---- helpers ----

    private fun withShell(builder: Any) {
        if (Build.VERSION.SDK_INT < 31) return
        try { builder.javaClass.getMethod("setContext", Context::class.java).invoke(builder, shell) } catch (_: Throwable) {}
    }

    private fun telephonyOut(): AudioDeviceInfo? = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
    } catch (_: Throwable) { null }

    private fun pstnInterceptable(): Boolean? = try {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        val svc = Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        svc.javaClass.getMethod("isPstnCallAudioInterceptable").invoke(svc) as Boolean
    } catch (_: Throwable) { null }

    private fun halParams(p: String) {
        val id = Binder.clearCallingIdentity()
        try { audio.setParameters(p) } catch (e: Throwable) { Log.w(TAG, "setParameters($p): $e") } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    private fun chip(): String {
        val s = ((if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "") + " " + Build.HARDWARE + " " + Build.BOARD).lowercase()
        return when {
            "qcom" in s || "qualcomm" in s || s.contains(Regex("\\b(sm|msm|sdm)\\d")) -> "qualcomm"
            "mediatek" in s || s.contains(Regex("\\bmt\\d")) -> "mediatek"
            "samsung" in s || "exynos" in s || "s5e" in s -> "exynos"
            else -> s.trim().ifEmpty { "unknown" }
        }
    }

    private fun pcmIn(rate: Int) = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()

    private fun pcmOut(rate: Int) = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()

    private fun bufIn(rate: Int) = maxOf(
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 50 * 8)

    private fun bufOut(rate: Int) = maxOf(
        AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 50 * 8)

    private fun closeQuietly(c: java.io.Closeable) = try { c.close() } catch (_: Throwable) {}

    companion object {
        private const val TAG = "PB-CallTapService"
        private const val MODE_CALL_REDIRECT = 5
        private const val USAGE_CALL_ASSISTANT = 17

        const val REDIRECT = "redirect"
        const val TELEPHONY = "telephony"
        const val SAMSUNG_TX = "samsung-tx"
        const val QCOM_INCALL = "qcom-incall"
        const val DOWNLINK = "downlink"

        /** Methods that also carry the iPhone voice into the call. */
        fun carriesUplink(method: String) = method != DOWNLINK
    }
}
