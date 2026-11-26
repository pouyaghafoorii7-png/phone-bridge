package com.phonebridge.android

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.math.sqrt

/**
 * Runs on the emulator in GitHub Actions (tests/android_emulator.sh). Shizuku is started with ADB
 * and an emulated phone call is active, as on a real phone. Results go to logcat with tag PB-TEST.
 */
@RunWith(AndroidJUnit4::class)
class CallTapTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val dev = UiDevice.getInstance(inst)

    private fun log(msg: String) = Log.i("PB-TEST", msg)

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(200) }
        return cond()
    }

    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { block(); done.countDown() }
        done.await()
    }

    @Before
    fun shizuku() {
        assertTrue("Shizuku is not running", waitFor(20_000) { CallTap.running() })
        if (!CallTap.granted()) {
            onMain { CallTap.requestPermission(ctx) }
            // The Shizuku dialog: title "Allow … to access Shizuku?", buttons "Allow all the time" / "Deny".
            dev.wait(Until.hasObject(By.clickable(true).text(Pattern.compile("(?i)deny"))), 15_000)
            val allow = dev.findObject(By.clickable(true).text(Pattern.compile("(?i)(allow.*time|always.*|allow)")))
                ?: dev.findObject(By.res("android", "button1"))
            log("shizuku dialog buttons: " + dev.findObjects(By.clickable(true)).joinToString { "'${it.text}'" })
            log("click: ${allow?.text}")
            allow?.click()
        }
        assertTrue("Shizuku permission not given", waitFor(15_000) { CallTap.granted() })
        onMain { CallTap.prepare(ctx) }
        assertTrue("call line service did not start", waitFor(30_000) { CallTap.ready() })
    }

    @Test
    fun serviceRunsAsShell() {
        val uid = CallTap.serviceUid()
        log("service uid = $uid")
        assertEquals("the call line service must have the shell identity", 2000, uid)
    }

    @Test
    fun callLineRecords() {
        log("phone: ${CallTap.info()}")
        val results = mutableListOf<String>()
        var opened = 0
        for (method in CallTap.methods()) {
            val (line, why) = CallTap.openLine(method)
            if (line == null) { results += "$method: FAILED ($why)"; continue }
            opened++
            val buf = ShortArray(AudioBridge.FRAME)
            var frames = 0
            var peak = 0.0
            val t0 = System.currentTimeMillis()
            while (frames < 50 && line.read(buf)) {
                frames++
                line.send(buf) // also push frames the other way
                var sum = 0.0; for (x in buf) sum += x.toDouble() * x
                peak = maxOf(peak, sqrt(sum / buf.size))
            }
            val ms = System.currentTimeMillis() - t0
            line.close()
            results += "$method: OPEN (uplink ${line.carriesUplink}), $frames frames in $ms ms, peak rms ${"%.1f".format(peak)}"
        }
        results.forEach { log(it) }
        assertTrue("no call line method could be opened: $results", opened > 0)
    }

    @Test
    fun audioBridgeSendsCallerSound() {
        val frames = AtomicInteger()
        val info = mutableListOf<String>()
        val bridge = AudioBridge(ctx, { frames.incrementAndGet() }) { k, d -> synchronized(info) { info += "$k=$d" } }
        onMain { bridge.start() }
        // Play a 1 s tone as "the iPhone user", like frames from the iPhone.
        val tone = ShortArray(AudioBridge.FRAME) { (8000 * kotlin.math.sin(2 * Math.PI * 440 * it / AudioBridge.RATE)).toInt().toShort() }
        repeat(25) { bridge.play(MuLaw.encode(tone)); Thread.sleep(40) }
        Thread.sleep(9_000) // longer than the 6 s silence check of the call line
        bridge.stop()
        log("AudioBridge: ${frames.get()} frames sent, info: $info")
        assertTrue("too few frames: ${frames.get()}", frames.get() > 150)
        assertTrue("no route info: $info", info.any { it.startsWith("route=") })
    }
}
