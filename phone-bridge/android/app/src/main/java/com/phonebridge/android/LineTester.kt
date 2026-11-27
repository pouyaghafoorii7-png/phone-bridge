package com.phonebridge.android

import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds out what the call line does on this phone, in one phone call.
 *
 * Phase 1 (the caller's voice): every method that the phone offers is opened for about 1.5 s while a
 * normal call is going on, and the sound that comes out of the call is measured. A method that gives
 * real sound carries the other person's voice; one that gives exact zeros does not.
 *
 * Phase 2 (our voice): the first methods that opened send beeps into the call, one beep for the first,
 * two for the second, and so on. The other person says how many they heard: that is the method whose
 * "voice into the call" works.
 *
 * Run it with the bridge stopped, in a call placed from this phone's own dialer.
 */
object LineTester {
    /** state: "sound" | "silent" | "refused". [beeps] is 0 when the method was not used in phase 2. */
    class Result(val code: String, val name: String, val state: String, val detail: String, val peak: Double, var beeps: Int = 0) {
        fun line(): String = buildString {
            append(name)
            append(": ")
            append(when (state) {
                "sound" -> "SOUND (level ${peak.toInt()})"
                "silent" -> "silent"
                else -> "refused ($detail)"
            })
            if (beeps > 0) append(" · $beeps beep${if (beeps > 1) "s" else ""}")
        }
    }

    const val LISTEN_FRAMES = 38 // about 1.5 s
    const val BEEP_METHODS = 5
    private const val SOUND_LEVEL = 60.0

    /**
     * Blocks until done. [onStep] gets a short sentence about what is happening now, [onResult] each
     * finished method (phase 1 and again, with beeps set, in phase 2). [cancelled] ends the test early.
     */
    fun run(onStep: (String) -> Unit, onResult: (Result) -> Unit, cancelled: () -> Boolean): List<Result> {
        val results = mutableListOf<Result>()
        val codes = CallTap.methods()
        LineLog.add("test: listening with ${codes.size} methods")

        // ---- phase 1: what comes out of the call
        for (code in codes) {
            if (cancelled()) break
            val name = LineMethods.label(code)
            onStep("Listening with $name…")
            val (line, err) = CallTap.openLine(code)
            val r = if (line == null) {
                Result(code, name, "refused", err.ifEmpty { "no reason" }.take(80), 0.0)
            } else {
                val peak = listen(line)
                line.close()
                Result(code, name, if (peak >= SOUND_LEVEL) "sound" else "silent", "", peak)
            }
            results += r
            LineLog.add("test: " + r.line())
            onResult(r)
        }

        // ---- phase 2: what goes into the call
        if (!cancelled()) {
            val usable = results.filter { it.state != "refused" }
            // Methods that gave sound first, so the useful ones get the first beep counts.
            val chosen = (usable.filter { it.state == "sound" } + usable.filter { it.state == "silent" }).take(BEEP_METHODS)
            chosen.forEachIndexed { i, r ->
                if (cancelled()) return@forEachIndexed
                val n = i + 1
                onStep("Sending $n beep${if (n > 1) "s" else ""} with ${r.name}. Ask the other person to count.")
                val (line, _) = CallTap.openLine(r.code)
                if (line != null) {
                    beep(line, n)
                    line.close()
                    r.beeps = n
                    LineLog.add("test: ${r.name} sent $n beep(s)")
                    onResult(r)
                }
            }
        }
        onStep(if (cancelled()) "Stopped." else "Done.")
        return results
    }

    /** Reads the call's sound for a short time and returns the loudest frame (rms). */
    private fun listen(line: CallTap.CallLine): Double {
        val buf = ShortArray(AudioBridge.FRAME)
        var peak = 0.0
        // A method that never gives a frame must not hold the test: close it from the side.
        val watchdog = thread(name = "pb-test-watch", isDaemon = true) {
            try { Thread.sleep(4000); line.close() } catch (_: InterruptedException) {}
        }
        var n = 0
        while (n < LISTEN_FRAMES && line.read(buf)) {
            peak = maxOf(peak, rms(buf))
            n++
        }
        watchdog.interrupt()
        return peak
    }

    /** Sends [count] beeps (250 ms of 880 Hz, 250 ms of quiet) into the call, then a pause. */
    private fun beep(line: CallTap.CallLine, count: Int) {
        // Keep reading the caller so the service never blocks on a full pipe.
        val drain = thread(name = "pb-test-drain", isDaemon = true) {
            val b = ShortArray(AudioBridge.FRAME)
            while (line.read(b)) { /* discard */ }
        }
        val frame = ShortArray(AudioBridge.FRAME)
        val quiet = ShortArray(AudioBridge.FRAME)
        var phase = 0.0
        val step = 2 * Math.PI * 880 / AudioBridge.RATE
        fun tone(frames: Int) = repeat(frames) {
            for (i in frame.indices) { frame[i] = (9000 * sin(phase)).toInt().toShort(); phase += step }
            line.send(frame)
            SystemClock.sleep(38)
        }
        fun pause(frames: Int) = repeat(frames) { line.send(quiet); SystemClock.sleep(38) }
        pause(6)
        repeat(count) { tone(7); pause(7) }
        pause(12)
        drain.interrupt()
    }

    private fun rms(f: ShortArray): Double {
        var sum = 0.0
        for (s in f) sum += s.toDouble() * s
        return sqrt(sum / f.size)
    }
}
