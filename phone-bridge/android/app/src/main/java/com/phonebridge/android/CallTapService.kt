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
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The call line, the iTell way. Shizuku or the built-in ADB starts this class in its own process with
 * the shell identity (uid 2000, the rights of `adb shell`). From there it takes the caller's voice out
 * of the call (VOICE_DOWNLINK or a phone-specific path) and puts the iPhone user's voice into it.
 *
 * Phones differ a lot, so there are 24 methods (see [LineMethods], named like iTell's picker). Each is
 * one backend (how the sound is captured and played) plus a local-audio recipe (mute the phone's own
 * mic, quiet its earpiece, pretend a headset is plugged in) so nothing is heard on the phone. The app
 * tries them in the order of [methods] and remembers the one that works.
 *
 * Everything this class changes on the phone is put back when the call line closes, and a small
 * journal file lets the next start undo the changes if this process was killed in the middle of a call.
 */
class CallTapService() : ICallTap.Stub() {
    private var given: Context? = null

    /** Shizuku 13+ gives a Context when the class has this constructor. */
    constructor(context: Context) : this() { given = context }

    private val shell: Context by lazy { ShellEnv.context(given) }
    private val audio: AudioManager by lazy { shell.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // ---- what is open now ----
    @Volatile private var running = false
    @Volatile private var session = 0
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var trackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION
    private var tx = Tx()
    private var capStereo = false
    private var voiceChild: Process? = null
    private var routeNow = "direct"
    private var echoGate = false
    @Volatile private var loudUntil = 0L
    private var lastSample = 0
    private val pumps = mutableListOf<Thread>()

    // ---- things we changed, so we can put them back ----
    private val undo = ArrayList<() -> Unit>()
    private val journal = ArrayList<String>()
    private var headsetOn = false
    private var headsetListed = false
    private var bgsOn = false
    private var callFwdOn = false
    private var spectroOn = false
    @Volatile private var quietRestore: (() -> Unit)? = null

    private val logBuf = ArrayDeque<String>()

    /** The format of the iPhone voice for this phone's call input: rate multiplier, stereo, divisor. */
    private class Tx(val mul: Int = 1, val stereo: Boolean = false, val div: Int = 1) {
        val identity get() = mul == 1 && !stereo && div == 1
        fun rate(base: Int) = base * mul / div
    }

    // =====================================================================================
    //  AIDL
    // =====================================================================================

    override fun uid(): Int = Process.myUid()

    override fun route(): String = routeNow

    override fun log(): String = synchronized(logBuf) { logBuf.joinToString("\n") }

    override fun info(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (sdk $sdk), chip ${soc()}, " +
            "telephony output ${telephonyOut() != null}, call interception ${pstnInterceptable()}, methods ${order().size}"

    override fun methods(): String {
        recoveredOnce
        return order().joinToString(",")
    }

    @Synchronized
    override fun open(method: String, rate: Int, down: ParcelFileDescriptor, up: ParcelFileDescriptor?): String {
        stopLocked()
        recoveredOnce
        val out = ParcelFileDescriptor.AutoCloseOutputStream(down)
        val inp = up?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        try {
            if (!available(method)) error("not possible on this phone")
            routeNow = "direct"; tx = Tx(); capStereo = false; echoGate = false; lastSample = 0
            openBackend(method, rate)
            takeLocalAudio(method)
            keepCallOffBluetooth()
            val r = record ?: error("no capture")
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("capture blocked by the system")
            track?.play()
        } catch (e: Throwable) {
            stopLocked()
            closeQuietly(out); inp?.let { closeQuietly(it) }
            val c = e.cause ?: e
            val why = (c.javaClass.simpleName + ": " + (c.message ?: "")).take(160)
            note("open $method failed: $why")
            return why
        }
        running = true
        val gen = ++session
        val r = record!!
        pumps += thread(name = "pb-down") { pumpDown(r, out, gen) }
        if (inp != null) pumps += thread(name = "pb-up") { pumpUp(inp, gen) }
        note("call line open: $method (${LineMethods.label(method)}) at $rate Hz, route $routeNow, tx ${tx.mul}/${tx.div}${if (tx.stereo) " stereo" else ""}")
        return ""
    }

    @Synchronized
    override fun close() = stopLocked()

    override fun destroy() {
        close()
        exitProcess(0)
    }

    // =====================================================================================
    //  What this phone can do
    // =====================================================================================

    private val sdk = Build.VERSION.SDK_INT
    private val maker = Build.MANUFACTURER.lowercase()

    private fun soc(): String =
        ((if (sdk >= 31) Build.SOC_MANUFACTURER else "") + " " + Build.HARDWARE).trim().lowercase()

    private fun isMtk(): Boolean =
        (sdk >= 31 && Build.SOC_MANUFACTURER.contains("mediatek", true)) || Build.HARDWARE.startsWith("mt", true)

    private fun isQcom(): Boolean =
        (sdk >= 31 && (Build.SOC_MANUFACTURER.contains("qti", true) || Build.SOC_MANUFACTURER.contains("qualcomm", true))) ||
            Build.HARDWARE.contains("qcom", true)

    private fun sysProp(key: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    } catch (_: Throwable) { "" }

    /** Samsung Exynos and MediaTek phones have an audio-HAL switch that forwards the call's sound. */
    private fun callFwdHere(): Boolean {
        if (isMtk()) return true
        if (maker == "samsung") {
            val s = (sysProp("ro.board.platform") + " " + sysProp("ro.hardware") + " " +
                (if (sdk >= 31) Build.SOC_MANUFACTURER else "")).lowercase()
            return "exynos" in s || "universal" in s || "samsung" in s
        }
        return false
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

    private fun methodExists(c: Class<*>, name: String, vararg params: Class<*>): Boolean =
        try { c.getMethod(name, *params); true } catch (_: Throwable) { false }

    private fun redirectApiHere(): Boolean = sdk >= 33 && pstnInterceptable() != false &&
        methodExists(AudioRecord.Builder::class.java, "setCallRedirectionMode", Int::class.javaPrimitiveType!!)

    private fun available(code: String): Boolean = when (code) {
        "k7", "q2", "w5", "h9", "y1", "n2" -> redirectApiHere()
        "e5" -> sdk >= 33 && methodExists(AudioManager::class.java, "getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
        "d4", "r3", "z8", "y6", "f6" -> telephonyOut() != null
        "s6" -> maker == "samsung" && methodExists(AudioAttributes.Builder::class.java, "semAddAudioTag", String::class.java)
        "c9" -> isQcom()
        "m4" -> isQcom() && telephonyOut() != null
        "b7", "l5" -> isMtk()
        "g8", "v2", "t4", "x6", "j3" -> callFwdHere()
        "a4", "p5" -> true
        else -> false // u3 and u8 (root) are not offered
    }

    /** iTell's own first choice for a phone: by Android version and maker. */
    private fun defaultCode(): String = when {
        sdk < 33 -> "r3"
        maker == "samsung" && sdk <= 34 -> "d4"
        maker == "google" || maker == "motorola" || maker == "samsung" -> "k7"
        else -> "r3"
    }

    /** The methods that can work here, best first: the clean ones, then the phone tricks, then the speaker. */
    private fun order(): List<String> {
        val vendor = buildList {
            if (isMtk()) { add("b7"); add("l5") }
            if (callFwdHere() && !isMtk()) add("g8")
            if (isQcom()) { add("c9"); add("m4") }
        }
        val clean = listOf("k7", "d4", "r3", "f6", "z8", "y6", "y1", "n2", "e5", "s6")
        val tricks = listOf("q2", "w5", "h9", "b7", "l5", "c9", "m4", "g8", "v2", "t4", "x6", "j3")
        val speaker = listOf("a4", "p5")
        return (listOf(defaultCode()) + vendor + clean + tricks + speaker).distinct().filter { available(it) }
    }

    // =====================================================================================
    //  Opening one method
    // =====================================================================================

    private fun openBackend(method: String, rate: Int) {
        when (method) {
            "k7", "q2", "w5", "h9" -> openRedirection(rate)
            "y1", "n2" -> openHybrid(rate)
            "e5" -> openHelper(rate)
            "d4", "r3", "z8", "y6" -> openDirect(rate, own = false)
            "f6" -> openDirect(rate, own = true)
            "s6" -> openSamsungTx(rate)
            "c9" -> openQcomIncall(rate)
            "m4" -> openQcomIncallStereo(rate)
            "b7" -> openMtkBgs(rate)
            "l5" -> openMtkBgsStereo(rate)
            "a4" -> openAcoustic(rate)
            "p5" -> openAcousticMic(rate)
            "g8" -> openCallFwd(rate, withDownlink = isMtk())
            "v2" -> openCallFwd(rate, withDownlink = true)
            "t4" -> { openCallFwd(rate, withDownlink = isMtk()); ownVoice(rate) }
            "x6" -> { openCallFwd(rate, withDownlink = true); ownVoice(rate) }
            "j3" -> openCallFwdPrimary(rate)
            else -> error("unknown method $method")
        }
    }

    private val ENC = AudioFormat.ENCODING_PCM_16BIT

    private fun fmtIn(rate: Int, stereo: Boolean = false) = AudioFormat.Builder().setEncoding(ENC).setSampleRate(rate)
        .setChannelMask(if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO).build()

    private fun fmtOut(rate: Int, stereo: Boolean = false) = AudioFormat.Builder().setEncoding(ENC).setSampleRate(rate)
        .setChannelMask(if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build()

    private fun bufIn(rate: Int, stereo: Boolean = false) = maxOf(
        AudioRecord.getMinBufferSize(rate, if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO, ENC),
        rate / 50 * 8 * (if (stereo) 2 else 1))

    private fun bufOut(rate: Int, stereo: Boolean = false) = maxOf(
        AudioTrack.getMinBufferSize(rate, if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO, ENC),
        rate / 50 * 2 * (if (stereo) 2 else 1) * 8)

    private fun withShell(builder: Any) {
        if (sdk < 31) return
        try { builder.javaClass.getMethod("setContext", Context::class.java).invoke(builder, shell) } catch (_: Throwable) {}
    }

    private val voiceAttrs: AudioAttributes by lazy {
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }

    private fun mediaAttrs(): AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** A capture from one audio source, or an exception saying why not. */
    @SuppressLint("MissingPermission")
    private fun newRecord(source: Int, rate: Int, stereo: Boolean = false): AudioRecord {
        val b = AudioRecord.Builder().setAudioSource(source).setAudioFormat(fmtIn(rate, stereo))
            .setBufferSizeInBytes(bufIn(rate, stereo))
        withShell(b)
        val r = b.build()
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); error("capture source $source not initialized") }
        return r
    }

    /** A playback track, or null when the phone refuses it. */
    private fun newTrack(attrs: AudioAttributes, rate: Int, stereo: Boolean = false): AudioTrack? = try {
        val b = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmtOut(rate, stereo))
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufOut(rate, stereo))
        withShell(b)
        val t = b.build()
        if (t.state == AudioTrack.STATE_INITIALIZED) t else { t.release(); null }
    } catch (_: Throwable) { null }

    private fun pinnedTrack(attrs: AudioAttributes, rate: Int, stereo: Boolean, dev: AudioDeviceInfo): AudioTrack? {
        val t = newTrack(attrs, rate, stereo) ?: return null
        return if (t.setPreferredDevice(dev)) t else { t.release(); null }
    }

    /** Android 13+ call redirection (Panda, Koala, Penguin, Otter): both directions in one go. */
    private fun openRedirection(rate: Int) {
        check(sdk >= 33) { "needs Android 13" }
        check(pstnInterceptable() != false) { "this phone does not allow call interception" }
        val pstn = try { AudioManager::class.java.getField("CALL_REDIRECT_PSTN").getInt(null) } catch (_: Throwable) { 1 }
        val intType = Int::class.javaPrimitiveType!!

        val capture = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", intType)
            .invoke(capture, MediaRecorder.AudioSource.VOICE_DOWNLINK)
        val rb = AudioRecord.Builder().setAudioFormat(fmtIn(rate)).setBufferSizeInBytes(bufIn(rate))
        withShell(rb)
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(rb, capture.build())
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", intType).invoke(rb, pstn)
        record = (rb.build() as AudioRecord).also { check(it.state == AudioRecord.STATE_INITIALIZED) { "redirect capture not initialized" } }

        val play = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioAttributes.Builder::class.java.getMethod("setSystemUsage", intType).invoke(play, USAGE_CALL_ASSISTANT)
        val tb = AudioTrack.Builder().setAudioAttributes(play.build()).setAudioFormat(fmtOut(rate))
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufOut(rate))
        withShell(tb)
        AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", intType).invoke(tb, pstn)
        track = (tb.build() as AudioTrack).also { check(it.state == AudioTrack.STATE_INITIALIZED) { "redirect track not initialized" } }
    }

    /** Squirrel: the redirection track for the iPhone voice, the plain downlink capture for the caller. */
    private fun openHybrid(rate: Int) {
        openRedirection(rate)
        try { record?.release() } catch (_: Throwable) {}
        record = newRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate)
    }

    /** Hedgehog: Android's own call-audio interception API. */
    private fun openHelper(rate: Int) {
        check(sdk >= 33) { "needs Android 13" }
        val r = AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
            .invoke(audio, fmtIn(rate)) as AudioRecord
        record = r
        check(r.state == AudioRecord.STATE_INITIALIZED) { "helper record not initialized" }
        val t = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            .invoke(audio, fmtOut(rate)) as AudioTrack
        track = t
        check(t.state == AudioTrack.STATE_INITIALIZED) { "helper track not initialized" }
    }

    /** The first rate the device lists that is a whole multiple (2 to 6) of ours, else ours. */
    private fun nearestMultiple(rate: Int, listed: List<Int>): Int {
        if (listed.isEmpty() || rate in listed) return rate
        return listed.firstOrNull { it % rate == 0 && it / rate in 2..6 } ?: rate
    }

    /** The device takes stereo only. */
    private fun stereoOnly(channels: List<Int>) = channels.isNotEmpty() && 1 !in channels && 2 in channels

    /** Dolphin, Rabbit, z8, y6 and Flamingo: a track pinned to the telephony output device. */
    private fun openDirect(rate: Int, own: Boolean) {
        record = newRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate)
        val dev = telephonyOut() ?: error("no telephony output on this phone")
        val rates = dev.sampleRates.toList()
        val chans = dev.channelCounts.toList()
        val mul = nearestMultiple(rate, rates) / rate
        val stereo = stereoOnly(chans)
        if (own) {
            pinnedTrack(voiceAttrs, rate * mul, stereo, dev)?.let {
                track = it; tx = Tx(mul, stereo, 1)
                note("telephony output in its own format: ${rate * mul} Hz ${if (stereo) "stereo" else "mono"}")
                return
            }
            note("telephony output refused its own format ${rate * mul} Hz; using $rate Hz mono")
        }
        pinnedTrack(voiceAttrs, rate, false, dev)?.let { track = it; return }
        if (!(mul == 1 && !stereo)) {
            note("telephony output refused $rate Hz mono; trying ${rate * mul} Hz ${if (stereo) "stereo" else "mono"}, converting")
            pinnedTrack(voiceAttrs, rate * mul, stereo, dev)?.let { track = it; tx = Tx(mul, stereo, 1); return }
        }
        error("telephony track not initialized or refused")
    }

    /** Giraffe: Samsung's VOICE_TX track, pinned to the telephony output when it allows. */
    private fun openSamsungTx(rate: Int) {
        record = newRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate)
        val b = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioAttributes.Builder::class.java.getMethod("semAddAudioTag", String::class.java).invoke(b, "VOICE_TX")
        val attrs = b.build()
        val dev = telephonyOut()
        if (dev != null) {
            val formats = mutableListOf(Tx())
            if (rate > 8000 && rate % 8000 == 0) formats += Tx(1, false, rate / 8000)
            for (f in formats) {
                val t = newTrack(attrs, f.rate(rate), f.stereo) ?: continue
                if (t.setPreferredDevice(dev)) {
                    track = t; tx = f
                    note("VOICE_TX track pinned to the telephony output at ${f.rate(rate)} Hz")
                    return
                }
                t.release()
                note("VOICE_TX track at ${f.rate(rate)} Hz refused the telephony output")
            }
        } else {
            note("no telephony output to pin the VOICE_TX track to")
        }
        track = newTrack(attrs, rate) ?: error("VOICE_TX track not initialized")
        note("VOICE_TX track unpinned (the system routes it)")
    }

    private fun setIncallMusic() {
        halParams("incall_music_enabled=true")
        later("hal incall_music_enabled=false") { halParams("incall_music_enabled=false") }
    }

    private fun rememberStreamVolume(stream: Int) {
        val before = audio.getStreamVolume(stream)
        later("vol $stream $before") { audio.setStreamVolume(stream, before, 0) }
    }

    /** Elephant: the audio HAL mixes "in-call music" into the call's uplink. */
    private fun openQcomIncall(rate: Int) {
        record = newRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate)
        setIncallMusic()
        track = newTrack(mediaAttrs(), rate) ?: error("in-call music track not initialized")
        try {
            rememberStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 8 / 10, 0)
        } catch (_: Throwable) {}
    }

    /** Zebra: the same, with a stereo track bound to the telephony output. */
    private fun openQcomIncallStereo(rate: Int) {
        val dev = telephonyOut() ?: error("no telephony output on this phone")
        track = pinnedTrack(voiceAttrs, rate, true, dev) ?: error("stereo telephony track refused")
        tx = Tx(1, true, 1)
        record = newRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate)
        setIncallMusic()
        note("in-call music: stereo track bound to the telephony output")
    }

    /** Cat: MediaTek's "BGS" switch mixes a voice track into the call; it must be re-sent every 2 s. */
    private fun openMtkBgs(rate: Int) {
        record = newRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate)
        track = newTrack(voiceAttrs, rate) ?: error("call track not initialized")
        bgsOn = true
        halParams(BGS_ON)
        later("hal $BGS_OFF") { bgsOn = false; halParams(BGS_OFF) }
        val gen = session + 1
        thread(name = "pb-bgs", isDaemon = true) {
            while (bgsOn && session <= gen) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@thread }
                if (bgsOn && session == gen) halParams(BGS_ON)
            }
        }
    }

    /** Leopard: Cat, with the caller taken from the stereo downlink capture when the phone allows it. */
    private fun openMtkBgsStereo(rate: Int) {
        openMtkBgs(rate)
        val st = try { newRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate, stereo = true) } catch (_: Throwable) { null }
        if (st != null && st.channelCount == 2) {
            try { record?.release() } catch (_: Throwable) {}
            record = st; capStereo = true
            note("mtk mix: the caller from the stereo downlink capture")
        } else {
            st?.release()
            note("mtk mix: stereo downlink capture refused, the mono one as in the Cat method")
        }
    }

    private fun speakerDevice(): AudioDeviceInfo? = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    } catch (_: Throwable) { null }

    private fun musicVolumeTo(percent: Int) {
        try {
            rememberStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * percent / 100, 0)
        } catch (_: Throwable) {}
    }

    /** a4: the caller from the downlink capture, the iPhone voice on the loudspeaker (the call's mic hears it). */
    private fun openAcoustic(rate: Int) {
        record = newRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate)
        val t = newTrack(mediaAttrs(), rate) ?: error("speaker track not initialized")
        track = t
        speakerDevice()?.let { t.setPreferredDevice(it) }
        musicVolumeTo(60)
        routeNow = "speaker"
    }

    /** Pig: everything acoustic, the caller too (microphone capture). Low quality. */
    private fun openAcousticMic(rate: Int) {
        var r: AudioRecord? = null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            r = try { newRecord(src, rate) } catch (_: Throwable) { null }
            if (r != null) { note("loudspeaker bridge: hearing through microphone source $src"); break }
            note("loudspeaker bridge: microphone source $src not available")
        }
        record = r ?: error("no microphone capture")
        val t = newTrack(mediaAttrs(), rate) ?: error("speaker track not initialized")
        track = t
        speakerDevice()?.let { t.setPreferredDevice(it) }
        musicVolumeTo(50)
        echoGate = true
        routeNow = "speaker"
    }

    // ---- call forwarding (Owl, Parrot, Swan, Turtle, Peacock): an audio-HAL switch ----

    private fun sysSet(p: String): Int {
        val id = Binder.clearCallingIdentity()
        return try {
            Class.forName("android.media.AudioSystem").getMethod("setParameters", String::class.java).invoke(null, p) as Int
        } catch (e: Throwable) {
            note("AudioSystem.setParameters($p) failed: ${(e.cause ?: e).javaClass.simpleName}")
            -1
        } finally { Binder.restoreCallingIdentity(id) }
    }

    private fun sysGet(k: String): String {
        val id = Binder.clearCallingIdentity()
        return try {
            (Class.forName("android.media.AudioSystem").getMethod("getParameters", String::class.java).invoke(null, k) as String?) ?: ""
        } catch (e: Throwable) {
            "error: " + (e.cause ?: e).javaClass.simpleName
        } finally { Binder.restoreCallingIdentity(id) }
    }

    /** The HAL answered "g_call_forwarding_enable=false" (or 0 / off): it kept the switch off. */
    private fun halSaysOff(said: String): Boolean = said.split(';').any { part ->
        val kv = part.trim().split('=', limit = 2)
        kv.size == 2 && kv[0] == "g_call_forwarding_enable" && kv[1].trim() in setOf("false", "0", "off")
    }

    private fun callFwd(on: Boolean, withSpectro: Boolean = true) {
        val p = "g_call_forwarding_enable=$on"
        val st = sysSet(p)
        halParams(p)
        callFwdOn = on
        val said = sysGet("g_call_forwarding_enable")
        note("call forwarding ${if (on) "on" else "off"}: HAL status $st, HAL says '${sysGet("call_forwarding")}' / '$said'")
        if (on) {
            if (withSpectro && !isMtk() && halSaysOff(said)) {
                spectroOn = true
                later("spectro") { sysSet("g_call_spectro_enable=false") }
                note("call forwarding: the HAL kept the first switch off, second switch (spectro) on: ${sysSet("g_call_spectro_enable=true")}")
            }
        } else if (spectroOn) {
            spectroOn = false
            sysSet("g_call_spectro_enable=false")
        }
    }

    private fun needInCallMode() {
        check(audio.mode == AudioManager.MODE_IN_CALL) { "call forwarding needs the phone's in-call mode (mode is ${audio.mode})" }
    }

    private fun anyRecord(sources: List<Int>, rate: Int, what: String): AudioRecord {
        for (s in sources) {
            val r = try { newRecord(s, rate) } catch (_: Throwable) { null }
            if (r != null) { note("$what: caller through capture source $s"); return r }
            note("$what: capture source $s not available")
        }
        error("call forwarding: no capture")
    }

    /** How much of the phone's own mic reaches the call (MediaTek probe): count of non-zero samples. */
    private fun micNonZero(): Int? = try {
        val r = newRecord(MediaRecorder.AudioSource.MIC, 16000)
        try {
            r.startRecording()
            val buf = ShortArray(3200)
            var got = 0; var nonZero = 0; var skip = 640
            val end = android.os.SystemClock.elapsedRealtime() + 600
            while (got < buf.size && android.os.SystemClock.elapsedRealtime() < end) {
                val n = r.read(buf, 0, buf.size - got)
                if (n <= 0) break
                for (i in 0 until n) { if (skip > 0) skip-- else if (buf[i].toInt() != 0) nonZero++ }
                got += n
            }
            if (got == 0) null else nonZero
        } finally { try { r.stop() } catch (_: Throwable) {}; r.release() }
    } catch (_: Throwable) { null }

    /** Owl and Parrot (and, with their own voice process, Swan and Turtle). */
    private fun openCallFwd(rate: Int, withDownlink: Boolean) {
        needInCallMode()
        val mtk = isMtk()
        val micBefore = if (mtk) micNonZero() else null
        callFwd(true)
        later("callfwd") { callFwd(false) }
        // MediaTek: did the switch close the phone's own mic? That decides the media volume below.
        var verdict = 'c' // 'a' mic closed by the switch, 'b' mic still open, 'c' unknown
        if (mtk) {
            var after: Int? = null
            for (ms in longArrayOf(150, 300, 400)) {
                Thread.sleep(ms)
                after = micNonZero()
                if (micBefore != null && after != null) {
                    if (after >= 8 && micBefore >= 8) { verdict = 'b'; break }
                    if (micBefore >= 8 && after < 8) verdict = 'a'
                }
            }
            note("call forwarding: phone mic before/after the switch ${micBefore ?: "-"}/${after ?: "-"} nonzero -> $verdict")
        }
        record = anyRecord((if (withDownlink) listOf(MediaRecorder.AudioSource.VOICE_DOWNLINK) else emptyList()) +
            listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.UNPROCESSED),
            rate, "call forwarding")
        trackUsage = if (mtk) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION
        note("call forwarding: playing as ${if (mtk) "media (MediaTek)" else "voice communication"}")
        track = newTrack(AudioAttributes.Builder().setUsage(trackUsage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(), rate)
            ?: error("call forwarding: output not initialized")
        if (mtk) mediaVolumeForCallFwd(verdict)
    }

    private fun mediaVolumeForCallFwd(verdict: Char) {
        try {
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = when (verdict) {
                'a' -> max
                'c' -> (max * 60 / 100).takeIf { it > now }
                else -> null
            }
            if (target == null) { note("call forwarding: media volume stays ($verdict)"); return }
            rememberStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            note("call forwarding: media volume ${if (verdict == 'a') "to full" else "to the middle"} for the call ($verdict)")
        } catch (e: Throwable) { note("call forwarding: media volume not changed: ${e.javaClass.simpleName}") }
    }

    /** Peacock: only the first HAL switch, the voice from its own process when it can. */
    private fun openCallFwdPrimary(rate: Int) {
        needInCallMode()
        val said = sysGet("g_call_forwarding_enable")
        check(said.contains("g_call_forwarding_enable=")) { "this HAL doesn't know the call-forwarding switch ('$said')" }
        callFwd(true, withSpectro = false)
        later("callfwd") { callFwd(false) }
        if (halSaysOff(sysGet("g_call_forwarding_enable"))) error("call forwarding: the HAL kept the switch off")
        record = anyRecord(listOf(MediaRecorder.AudioSource.VOICE_DOWNLINK, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.UNPROCESSED), rate, "call forwarding (first switch only)")
        voiceChild = startVoiceChild(rate, 0)
        if (voiceChild != null) { note("call forwarding (first switch only): voice played by its own process"); return }
        trackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION
        track = newTrack(voiceAttrs, rate) ?: error("call forwarding: output not initialized")
        note("call forwarding (first switch only): voice track on the primary output")
    }

    /** Swan and Turtle: the voice is played by a separate process (some HALs only forward that). */
    private fun ownVoice(rate: Int) {
        val child = startVoiceChild(rate, trackUsage) ?: return
        try { track?.release() } catch (_: Throwable) {}
        track = null
        voiceChild = child
        note("call forwarding: voice played by its own process (usage $trackUsage)")
    }

    private fun startVoiceChild(rate: Int, usage: Int): Process? {
        val cp = System.getProperty("java.class.path")?.split(':')?.firstOrNull { it.isNotBlank() && File(it).exists() }
        if (cp == null) { note("voice process: APK path unknown, track in this process"); return null }
        val p = try {
            ProcessBuilder("app_process", "-Djava.class.path=$cp", "/system/bin", "--nice-name=ghafoorii_voice",
                ShellVoicePlayer::class.java.name, rate.toString(), usage.toString()).redirectErrorStream(true).start()
        } catch (e: Throwable) { note("voice process didn't start: ${e.javaClass.simpleName}, track in this process"); return null }
        val first = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        thread(name = "pb-voicelog", isDaemon = true) {
            try {
                val br = p.inputStream.bufferedReader()
                first.set(br.readLine()); latch.countDown()
                while (true) { note("voice process: " + (br.readLine() ?: break)) }
            } catch (_: Throwable) { latch.countDown() }
        }
        latch.await(3, TimeUnit.SECONDS)
        val s = first.get()
        if (s != null && s.startsWith("ready")) { note("voice process: $s"); return p }
        note("voice process not ready (${s ?: "no answer in 3 s"}), track in this process")
        try { p.outputStream.close() } catch (_: Throwable) {}
        p.destroy()
        return null
    }

    // =====================================================================================
    //  Local audio: nothing should be heard on, or leak from, the phone
    // =====================================================================================

    /** What iTell does per method so the phone stays quiet (exact copy of its recipe). */
    private fun takeLocalAudio(code: String) {
        when (code) {
            "z8", "y6" -> muteMic()
            "w5" -> { rememberEarpiece(); headsetOn = fakeHeadset(true, true); muteMic(); quietEarpiece(0); muteLocal() }
            "q2" -> { muteMic(); quietEarpiece(0); muteLocal() }
            "h9" -> {
                rememberEarpiece(); headsetOn = fakeHeadset(true, false)
                if (!headsetOn) muteMic()
                quietEarpiece(0); muteLocal()
            }
            "s6", "m4", "c9", "f6", "d4" -> { muteMic(); quietEarpiece(0) }
            "l5", "b7" -> { rememberEarpiece(); headsetOn = fakeHeadset(true, false); loudEarpiece() }
            "a4" -> quietEarpiece(0)
            "p5" -> quietEarpiece(3)
            "x6", "v2", "t4", "r3", "n2", "j3", "g8" -> Unit
            else -> muteLocal() // k7, y1, e5
        }
    }

    /** Android 13+: call-redirect mode, so the phone's own mic and earpiece are out of the call. */
    private fun muteLocal() {
        if (sdk < 33) return
        val before = audio.mode
        if (before == MODE_CALL_REDIRECT) return
        try { audio.mode = MODE_CALL_REDIRECT } catch (e: Throwable) { note("MODE_CALL_REDIRECT failed: ${e.message}"); return }
        note("local mute: mode $before -> ${audio.mode}")
        later("mode $before") { if (audio.mode == MODE_CALL_REDIRECT) audio.mode = before }
    }

    private fun muteMic() {
        val before = try { audio.isMicrophoneMute } catch (_: Throwable) { false }
        try { audio.isMicrophoneMute = true } catch (e: Throwable) { note("mic mute failed: ${e.message}"); return }
        later("mic ${if (before) 1 else 0}") { audio.isMicrophoneMute = before }
    }

    private fun rememberEarpiece() {
        try { rememberStreamVolume(AudioManager.STREAM_VOICE_CALL) } catch (_: Throwable) {}
    }

    private val quietFile = File("/data/local/tmp/ghafoorii-quiet-breaks")

    /** On some phones, lowering the call volume also silences the downlink capture; we remember that. */
    private fun quietBreaksHere(): Boolean = try { quietFile.exists() && quietFile.readText().trim() == Build.FINGERPRINT } catch (_: Throwable) { false }

    private fun quietEarpiece(div: Int) {
        if (quietBreaksHere()) { note("call volume left alone: on this phone lowering it silenced the caller before"); return }
        try {
            val stream = AudioManager.STREAM_VOICE_CALL
            val before = audio.getStreamVolume(stream)
            val min = audio.getStreamMinVolume(stream)
            val target = if (div > 0) maxOf(min + 1, audio.getStreamMaxVolume(stream) / div) else min
            audio.setStreamVolume(stream, target, 0)
            note("call volume $before -> ${audio.getStreamVolume(stream)}")
            val restore = { audio.setStreamVolume(stream, before, 0) }
            quietRestore = restore
            later("vol $stream $before") { restore(); quietRestore = null }
        } catch (e: Throwable) { note("call volume failed: ${e.message}") }
    }

    private fun loudEarpiece() {
        try {
            val stream = AudioManager.STREAM_VOICE_CALL
            val before = audio.getStreamVolume(stream)
            audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), 0)
            note("call volume $before -> ${audio.getStreamVolume(stream)} for the BGS mix")
            later("vol $stream $before") { audio.setStreamVolume(stream, before, 0) }
        } catch (e: Throwable) { note("call volume failed: ${e.message}") }
    }

    /** Pretends a wired headset is plugged in, so the call goes there and not to the earpiece or speaker. */
    private fun fakeHeadset(on: Boolean, headphone: Boolean): Boolean {
        val devs = if (headphone) intArrayOf(DEVICE_OUT_WIRED_HEADPHONE) else intArrayOf(DEVICE_OUT_WIRED_HEADSET, DEVICE_IN_WIRED_HEADSET)
        val intType = Int::class.javaPrimitiveType!!
        var failed = false
        for (d in devs) {
            val ok = try {
                AudioManager::class.java.getMethod("setWiredDeviceConnectionState", intType, intType, String::class.java, String::class.java)
                    .invoke(audio, d, if (on) 1 else 0, HEADSET_ADDRESS, "Ghafoorii")
                true
            } catch (_: Throwable) {
                try {
                    val c = Class.forName("android.media.AudioDeviceAttributes")
                    val attrs = c.getConstructor(intType, String::class.java, String::class.java).newInstance(d, HEADSET_ADDRESS, "Ghafoorii")
                    AudioManager::class.java.getMethod("setWiredDeviceConnectionState", c, intType).invoke(audio, attrs, if (on) 1 else 0)
                    true
                } catch (e: Throwable) {
                    if (on) note("virtual headset ${Integer.toString(d, 16)}: ${(e.cause ?: e).message}")
                    false
                }
            }
            if (!ok) failed = true
        }
        if (!on) return !failed
        if (!failed) later("headset ${if (headphone) 1 else 0}") { fakeHeadset(false, headphone) }
        var listed = false
        val end = System.nanoTime() + 1_500_000_000L
        while (!failed && System.nanoTime() < end) {
            if (headsetShown()) { listed = true; break }
            Thread.sleep(50)
        }
        if (listed) Thread.sleep(150)
        note(if (listed) "virtual headset on" else "virtual headset did not appear")
        return listed
    }

    private fun headsetShown(): Boolean = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
    } catch (_: Throwable) { false }

    /** If the call is on a Bluetooth device, move it to the earpiece (or the virtual headset) while we have it. */
    private fun keepCallOffBluetooth() {
        if (sdk < 31) return
        try {
            if (audio.mode != AudioManager.MODE_IN_CALL) return
            val cur = audio.communicationDevice ?: return
            val bt = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
            if (cur.type !in bt) return
            val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
            val avail = audio.availableCommunicationDevices
            val target = (if (headsetOn) avail.firstOrNull { it.type in wired } else null)
                ?: avail.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (target == null) { note("call stays on Bluetooth: no earpiece to move it to"); return }
            val ok = audio.setCommunicationDevice(target)
            note("call moved off Bluetooth: $ok")
            if (ok) later(null) { audio.clearCommunicationDevice() }
        } catch (e: Throwable) { note("moving the call off Bluetooth failed: ${e.message}") }
    }

    // =====================================================================================
    //  Pumps
    // =====================================================================================

    private fun rms(s: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += s[i].toDouble() * s[i]
        return Math.sqrt(sum / maxOf(1, n))
    }

    /** The caller's voice: capture to the pipe, always as mono 16-bit little-endian. */
    private fun pumpDown(r: AudioRecord, out: OutputStream, gen: Int) {
        val frame = AudioBridge.FRAME
        val ch = if (capStereo) 2 else 1
        val buf = ShortArray(frame * ch)
        val mono = ShortArray(frame)
        val bytes = ByteArray(frame * 2)
        val t0 = System.currentTimeMillis()
        var heard = false
        try {
            while (running && gen == session) {
                var off = 0
                while (off < buf.size && running && gen == session) {
                    val n = r.read(buf, off, buf.size - off)
                    if (n <= 0) return
                    off += n
                }
                if (off < buf.size) continue
                for (i in 0 until frame) mono[i] = if (capStereo) ((buf[2 * i] + buf[2 * i + 1]) / 2).toShort() else buf[i]
                if (!heard && rms(mono, frame) > 1.0) heard = true
                // Lowering the call volume silenced the caller on some phones: put it back and remember.
                val q = quietRestore
                if (q != null && !heard && System.currentTimeMillis() - t0 > 3000) {
                    quietRestore = null
                    try { q() } catch (_: Throwable) {}
                    try { quietFile.writeText(Build.FINGERPRINT) } catch (_: Throwable) {}
                    note("downlink silent after lowering the call volume: volume put back, remembered for this phone")
                }
                if (echoGate && System.currentTimeMillis() < loudUntil) for (i in 0 until frame) mono[i] = (mono[i] * 0.15).toInt().toShort()
                for (i in 0 until frame) { bytes[2 * i] = mono[i].toByte(); bytes[2 * i + 1] = (mono[i].toInt() shr 8).toByte() }
                out.write(bytes)
            }
        } catch (_: IOException) {
            // The app closed its end.
        } finally {
            closeQuietly(out)
        }
    }

    /** The iPhone voice: pipe to the call (or the speaker, or the voice process). */
    private fun pumpUp(inp: InputStream, gen: Int) {
        val din = DataInputStream(inp)
        val frame = ByteArray(AudioBridge.FRAME * 2)
        val sh = ShortArray(AudioBridge.FRAME)
        try {
            while (running && gen == session) {
                din.readFully(frame)
                if (echoGate) {
                    for (i in sh.indices) sh[i] = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xff)).toShort()
                    if (rms(sh, sh.size) > 500) loudUntil = System.currentTimeMillis() + 300
                }
                val child = voiceChild
                val t = track
                when {
                    child != null -> { child.outputStream.write(frame); child.outputStream.flush() }
                    t != null -> { val o = toTx(frame); t.write(o, 0, o.size) }
                }
            }
        } catch (_: IOException) {
            // The app closed its end, or the call ended.
        } finally {
            closeQuietly(inp)
        }
    }

    /** Converts one 16 kHz mono frame to this phone's call-input format (rate, stereo), like iTell's toTx. */
    private fun toTx(frame: ByteArray): ByteArray {
        val t = tx
        if (t.identity) return frame
        val n = frame.size / 2
        val s = ShortArray(n) { ((frame[2 * it + 1].toInt() shl 8) or (frame[2 * it].toInt() and 0xff)).toShort() }
        val out: ShortArray
        if (t.div > 1) {
            val m = n / t.div
            val mono = ShortArray(m) { i -> var sum = 0; for (k in 0 until t.div) sum += s[i * t.div + k]; (sum / t.div).toShort() }
            out = if (t.stereo) ShortArray(m * 2) { mono[it / 2] } else mono
        } else {
            val ch = if (t.stereo) 2 else 1
            out = ShortArray(n * t.mul * ch)
            var o = 0
            var prev = lastSample
            for (i in 0 until n) {
                val cur = s[i].toInt()
                for (k in 1..t.mul) {
                    val v = (prev + (cur - prev) * k / t.mul).toShort()
                    repeat(ch) { out[o++] = v }
                }
                prev = cur
            }
            lastSample = prev
        }
        val b = ByteArray(out.size * 2)
        for (i in out.indices) { b[2 * i] = out[i].toByte(); b[2 * i + 1] = (out[i].toInt() shr 8).toByte() }
        return b
    }

    // =====================================================================================
    //  Stopping and putting everything back
    // =====================================================================================

    private fun stopLocked() {
        val wasRunning = running
        running = false
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        record = null
        track = null
        voiceChild?.let {
            try { it.outputStream.close() } catch (_: Throwable) {}
            try { it.destroy() } catch (_: Throwable) {}
        }
        voiceChild = null
        runUndo()
        for (t in pumps) try { t.join(500) } catch (_: Throwable) {}
        pumps.clear()
        tx = Tx(); capStereo = false; echoGate = false; routeNow = "direct"; lastSample = 0
        headsetOn = false; quietRestore = null
        if (wasRunning) note("call line closed")
    }

    /** Remember how to undo a change; `recovery` is a journal line for the next start if we are killed. */
    private fun later(recovery: String?, block: () -> Unit) {
        undo += block
        if (recovery != null) {
            journal += recovery
            try { File(JOURNAL).writeText(journal.joinToString("\n")) } catch (_: Throwable) {}
        }
    }

    private fun runUndo() {
        for (b in undo.asReversed()) try { b() } catch (_: Throwable) {}
        undo.clear()
        journal.clear()
        try { File(JOURNAL).delete() } catch (_: Throwable) {}
    }

    /** Puts back what an earlier run left changed (it was killed during a call). */
    private val recoveredOnce: Boolean by lazy {
        try {
            val f = File(JOURNAL)
            if (f.exists()) {
                val lines = f.readLines()
                note("undoing ${lines.size} change(s) an earlier run left")
                for (l in lines.asReversed()) try {
                    val p = l.split(' ')
                    when (p[0]) {
                        "vol" -> audio.setStreamVolume(p[1].toInt(), p[2].toInt(), 0)
                        "mic" -> audio.isMicrophoneMute = p[1] == "1"
                        "mode" -> if (audio.mode == MODE_CALL_REDIRECT) audio.mode = p[1].toInt()
                        "hal" -> halParams(l.substring(4))
                        "headset" -> fakeHeadset(false, p[1] == "1")
                        "callfwd" -> callFwd(false)
                        "spectro" -> sysSet("g_call_spectro_enable=false")
                    }
                } catch (_: Throwable) {}
                f.delete()
            }
        } catch (_: Throwable) {}
        true
    }

    private fun halParams(p: String) {
        val id = Binder.clearCallingIdentity()
        try { audio.setParameters(p) } catch (e: Throwable) { note("setParameters($p) failed: ${e.message}") } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    private fun note(msg: String) {
        Log.i(TAG, msg)
        synchronized(logBuf) { logBuf.addLast(msg); while (logBuf.size > 80) logBuf.removeFirst() }
    }

    private fun closeQuietly(c: Closeable) = try { c.close() } catch (_: Throwable) {}

    companion object {
        private const val TAG = "PB-CallTapService"
        private const val MODE_CALL_REDIRECT = 5
        private const val USAGE_CALL_ASSISTANT = 17
        private const val BGS_ON = "Set_BGS_UL_Mute=0;Set_BGS_DL_Mute=1"
        private const val BGS_OFF = "Set_BGS_UL_Mute=1;Set_BGS_DL_Mute=0"
        private const val DEVICE_OUT_WIRED_HEADSET = 4
        private const val DEVICE_OUT_WIRED_HEADPHONE = 8
        private const val DEVICE_IN_WIRED_HEADSET = -2147483632
        private const val HEADSET_ADDRESS = "ghafoorii"
        private const val JOURNAL = "/data/local/tmp/ghafoorii-undo"
    }
}
