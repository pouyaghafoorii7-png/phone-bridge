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
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Moves a cellular call's sound between this phone and the iPhone without root.
 *
 * Best (the iTell way, needs Shizuku): the call line itself, both directions. The caller's voice
 * comes out of the call (VOICE_DOWNLINK) and the iPhone user's voice goes into it (telephony
 * output, call redirection, or the phone maker's path). Nothing plays on the speaker, nothing is
 * heard by the microphone. See CallTapService for the methods; the one that works is remembered.
 *
 * Fallback (no Shizuku, or the phone blocks every method): the call runs on the speaker (or a
 * loopback cable); we record the caller with the microphone and play the iPhone voice on the speaker,
 * where the call's own microphone picks it up. That needs the Ghafoorii call audio accessibility service.
 */
class AudioBridge(
    private val ctx: Context,
    private val sendFrame: (ByteArray) -> Unit,
    private val onInfo: (String, String) -> Unit = { _, _ -> }, // (kind, detail) for the iPhone
    /** True once the call is answered. A line is not judged silent before that (it rings first). */
    private val isActive: () -> Boolean = { true },
) {
    companion object {
        const val RATE = 16000
        const val FRAME = 640 // 40 ms

        private const val PREFS = "pb"
        private const val KEY_WORKED = "line_method"
        const val KEY_FORCED = "line_method_forced"

        /** The method the user picked on the setup screen, or "" for automatic. */
        fun forcedMethod(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FORCED, "") ?: ""

        fun setForcedMethod(ctx: Context, m: String) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FORCED, m).apply()

        fun workedMethod(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WORKED, "") ?: ""

        private const val KEY_FAILS = "line_fails"

        /** How many calls each method failed on (silent line or refused), as "k7:2,d4:1". */
        fun failures(ctx: Context): Map<String, Int> =
            (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAILS, "") ?: "")
                .split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }.toMap()

        private fun saveFailures(ctx: Context, m: Map<String, Int>) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FAILS, m.filterValues { it > 0 }.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

        /** Forget what failed, so automatic mode tries every method again. */
        fun resetResults(ctx: Context) {
            saveFailures(ctx, emptyMap())
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_WORKED).apply()
        }

        /** A method skipped by automatic mode after it failed on this many calls. */
        const val SKIP_AFTER = 2

        /** At most this many methods are tried during one call; the rest wait for a later call or a pick. */
        const val AUTO_TRIES = 4
    }

    @Volatile private var running = false
    /** Set by [pump] once the source gave real sound while the call was answered. */
    @Volatile private var verified = false
    @Volatile private var loudUntil = 0L
    private val queue = LinkedBlockingQueue<ShortArray>(64)
    @Volatile private var current: PcmSource? = null
    /** Where the iPhone voice goes: into the call line, or to the speaker track. */
    @Volatile private var sink: ((ShortArray) -> Unit)? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var routed = false
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start() {
        running = true
        thread(name = "pb-capture") { capture() }
        thread(name = "pb-playback") { playback() }
    }

    // ---- caller -> iPhone ----

    private fun capture() {
        // 1. The call line through Shizuku, method by method, until one gives real sound.
        var why = if (CallTap.ready()) "" else CallTap.state()
        if (CallTap.ready()) {
            for (method in lineOrder()) {
                if (!running) return
                val (line, err) = CallTap.openLine(method)
                if (line == null) { why = "$method: $err"; noteResult(method, ok = false); continue }
                current = line
                // The service puts the iPhone voice into the call (or plays it on the speaker for the
                // speaker methods); we only hand it the frames.
                sink = { line.send(it) }
                onInfo("route", line.route)
                // The call line gives exact zeros when the phone blocks it: allow 4 s of an answered call.
                if (pump(line, window = 100, floor = 1.0)) {
                    if (verified) noteResult(method, ok = true) // real sound came through before the call ended
                    return // the call ended while the line worked
                }
                line.close()
                current = null
                sink = null
                if (!running) return
                noteResult(method, ok = false)
                why = "$method: silent"
            }
        }

        // 2. Fallback: the microphone, with the call on the speaker (or a cable).
        onInfo("direct", why.ifEmpty { "none" })
        val wired = speakerSink()
        val mics = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "voice recognition",
            MediaRecorder.AudioSource.MIC to "microphone",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "voice communication",
            MediaRecorder.AudioSource.CAMCORDER to "camcorder",
        )
        var opened = false
        for ((src, name) in mics) {
            if (!running) return
            val m = mic(src, name, echo = !wired) ?: continue
            opened = true
            current = m
            if (pump(m, window = 50, floor = 40.0)) return
            m.close()
            current = null
        }
        if (running) onInfo("problem", if (opened) "silent" else "mic")
        while (running) Thread.sleep(200) // keep playing the iPhone voice even without capture
    }

    /**
     * Sends frames from `src` to the iPhone until the call ends (true) or the source turns out silent
     * in its first `window` frames (false: try the next one).
     */
    private fun pump(src: PcmSource, window: Int, floor: Double): Boolean {
        val buf = ShortArray(FRAME)
        var frames = 0
        var heard = 0.0
        verified = false
        while (running) {
            if (!src.read(buf)) return !running
            if (frames < window && isActive()) {
                heard = maxOf(heard, rms(buf))
                if (++frames == window) {
                    if (heard < floor) return false
                    verified = true
                    onInfo("source", src.name)
                }
            }
            // Echo gate for the microphone on the speaker: while the iPhone user's voice plays,
            // turn our capture down so they do not hear themselves.
            if (src is MicSource && src.echo && System.currentTimeMillis() < loudUntil) {
                for (i in buf.indices) buf[i] = (buf[i] * 0.15).toInt().toShort()
            }
            sendFrame(MuLaw.encode(buf))
        }
        return true
    }

    private fun lineOrder(): List<String> {
        val all = CallTap.methods()
        val forced = forcedMethod(ctx)
        if (forced.isNotEmpty() && forced in all) return listOf(forced)
        val worked = prefs.getString(KEY_WORKED, "") ?: ""
        val fails = failures(ctx)
        val rest = all.filter { it != worked && (fails[it] ?: 0) < SKIP_AFTER }
        return ((if (worked in all) listOf(worked) else emptyList()) + rest).take(AUTO_TRIES)
    }

    /** Remember what happened, so the next call starts with the method that works and skips the dead ones. */
    private fun noteResult(method: String, ok: Boolean) {
        val m = failures(ctx).toMutableMap()
        if (ok) {
            m.remove(method)
            prefs.edit().putString(KEY_WORKED, method).apply()
        } else {
            m[method] = (m[method] ?: 0) + 1
            if (prefs.getString(KEY_WORKED, "") == method) prefs.edit().remove(KEY_WORKED).apply()
        }
        saveFailures(ctx, m)
    }

    private class MicSource(private val r: AudioRecord, override val name: String, val echo: Boolean) : PcmSource {
        override val direct = false
        override fun read(buf: ShortArray): Boolean {
            var off = 0
            while (off < FRAME) {
                val n = r.read(buf, off, FRAME - off)
                if (n <= 0) return false
                off += n
            }
            return true
        }
        override fun close() {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun mic(source: Int, name: String, echo: Boolean): PcmSource? {
        val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minRec, FRAME * 8))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?.also { it.startRecording() }
        } catch (e: Exception) { null } ?: return null
        return MicSource(r, name, echo)
    }

    // ---- iPhone -> caller ----

    /** The call on the speaker (or cable) and the iPhone voice on a local track. True = cable. */
    private fun speakerSink(): Boolean {
        val wired = wiredDevice()
        route(wired)
        routed = true
        onInfo("route", if (wired != null) "cable" else "speaker")
        val minTrack = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minTrack, FRAME * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        track = t
        sink = { t.write(it, 0, FRAME) }
        return wired != null
    }

    private fun playback() {
        val silence = ShortArray(FRAME)
        var primed = false
        while (running) {
            val out = sink
            if (out == null) { Thread.sleep(10); continue }
            if (!primed) {
                if (queue.size < 3) { Thread.sleep(10); continue } // ~120 ms jitter buffer
                primed = true
            }
            val f = queue.poll(80, TimeUnit.MILLISECONDS)
            if (f == null) {
                primed = false
                out(silence)
                continue
            }
            if (rms(f) > 500) loudUntil = System.currentTimeMillis() + 300
            out(f)
        }
    }

    /** Plays a short DTMF tone into the call, the same path as the iPhone voice (uplink or speaker). */
    fun playTone(digit: String) {
        if (!running) return
        val f = when (digit) {
            "1" -> 697 to 1209; "2" -> 697 to 1336; "3" -> 697 to 1477
            "4" -> 770 to 1209; "5" -> 770 to 1336; "6" -> 770 to 1477
            "7" -> 852 to 1209; "8" -> 852 to 1336; "9" -> 852 to 1477
            "*" -> 941 to 1209; "0" -> 941 to 1336; "#" -> 941 to 1477
            else -> return
        }
        val total = RATE / 1000 * 180 // 180 ms
        var i = 0
        while (i < total) {
            val frame = ShortArray(FRAME)
            for (j in frame.indices) {
                val t = (i + j).toDouble() / RATE
                val v = (Math.sin(2 * Math.PI * f.first * t) + Math.sin(2 * Math.PI * f.second * t)) * 0.3
                frame[j] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            queue.offer(frame)
            i += FRAME
        }
    }

    fun play(mulaw: ByteArray) {
        if (!running || mulaw.size != FRAME) return
        if (queue.size > 12) queue.clear() // too far behind: drop to keep the delay low
        queue.offer(MuLaw.decode(mulaw))
    }

    fun stop() {
        running = false
        sink = null
        current?.close() // also wakes the capture thread from a blocked read
        current = null
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        track = null
        if (routed) unroute()
        routed = false
    }

    // ---- routing ----

    /** A headset or a loopback cable on the headphone jack / USB-C adapter. */
    private fun wiredDevice(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    /** The call goes to the cable when one is plugged in (nothing audible), otherwise to the speaker. */
    private fun route(wired: AudioDeviceInfo?) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val target = am.availableCommunicationDevices.firstOrNull {
                    if (wired != null) it.type == wired.type else it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                target?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = wired == null
            }
        } catch (_: Exception) {}
    }

    private fun unroute() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {}
    }

    private fun rms(f: ShortArray): Double {
        var sum = 0.0
        for (s in f) sum += s.toDouble() * s
        return sqrt(sum / f.size)
    }
}
