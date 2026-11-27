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

    /**
     * A call that the phone places stays "dialing" in the phone-state broadcast even when it is answered.
     * The service reads the phone's own call state; the emulated call here is answered, so it must say "active".
     */
    @Test
    fun callPhaseSeesTheAnsweredCall() {
        val raw = dev.executeShellCommand("dumpsys telephony.registry")
        raw.lines().filter { it.contains("call state", ignoreCase = true) || it.contains("mCallState") }
            .take(14).forEach { log("registry: ${it.trim()}") }
        val phase = CallTap.callPhase()
        log("call phase = '$phase'")
        assertEquals("the answered call must read as active", "active", phase)
    }

    /** The in-app test: it must find sound in the emulated call (the emulator gives a test tone). */
    @Test
    fun lineTesterFindsSound() {
        val results = LineTester.run({ }, { }, { false })
        results.forEach { log("tester: ${it.line()}") }
        assertTrue("the tester found no method with sound", results.any { it.state == "sound" })
    }

    /**
     * The built-in ADB path without the pairing step: start ShellMain as the shell user (what the
     * "shell:" command of the ADB connection does) and wait until it hands its binder to the app
     * through ShellBinderProvider. The pairing and the TLS connection itself need a real phone.
     */
    @Test
    fun builtInAdbPathHandsBinderBack() {
        AdbShell.stop()
        val cmd = "app_process " + AdbShell.appProcessArgs(ctx)
        log("starting like the built-in ADB does: $cmd")
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        Thread {
            try { android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes() } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
        val attached = waitFor(25_000) { AdbShell.ready() }
        val svc = AdbShell.service()
        log("attached=$attached uid=${svc?.uid()} methods=${svc?.methods()}")
        assertTrue("the shell process did not hand its binder to the app", attached)
        assertEquals("the built-in service must have the shell identity", 2000, svc?.uid())
        AdbShell.stop()
    }

    @Test
    fun callLineRecords() {
        log("phone: ${CallTap.info()}")
        val offered = CallTap.methods()
        log("methods offered here (${offered.size}): " + offered.joinToString(" ") { "$it=${LineMethods.label(it)}" })
        val results = mutableListOf<String>()
        var opened = 0
        for (method in offered) {
            val (line, why) = CallTap.openLine(method)
            if (line == null) { results += "METHOD $method ${LineMethods.label(method)}: REFUSED ($why)"; continue }
            opened++
            val buf = ShortArray(AudioBridge.FRAME)
            var frames = 0
            var peak = 0.0
            val t0 = System.currentTimeMillis()
            while (frames < 25 && line.read(buf)) {
                frames++
                line.send(buf) // also push frames the other way
                var sum = 0.0; for (x in buf) sum += x.toDouble() * x
                peak = maxOf(peak, sqrt(sum / buf.size))
            }
            val ms = System.currentTimeMillis() - t0
            line.close()
            results += "METHOD $method ${LineMethods.label(method)}: OPEN (${line.route}), $frames frames in $ms ms, peak rms ${"%.1f".format(peak)}"
        }
        results.forEach { log(it) }
        // What the service itself said, so a refusal can be understood from this log.
        CallTap.log().lines().takeLast(60).forEach { log("service: $it") }
        log("SUMMARY: ${offered.size} methods offered, $opened opened")
        assertTrue("no call line method could be opened: $results", opened > 0)
    }

    @Test
    fun audioBridgeSendsCallerSound() {
        val frames = AtomicInteger()
        val info = mutableListOf<String>()
        val bridge = AudioBridge(ctx, { frames.incrementAndGet() }, { k, d -> synchronized(info) { info += "$k=$d" } }, { true })
        onMain { bridge.start() }
        // Play a 1 s tone as "the iPhone user", like frames from the iPhone.
        val tone = ShortArray(AudioBridge.FRAME) { (8000 * kotlin.math.sin(2 * Math.PI * 440 * it / AudioBridge.RATE)).toInt().toShort() }
        repeat(25) { bridge.play(MuLaw.encode(tone)); Thread.sleep(40) }
        Thread.sleep(9_000) // longer than the 4 s silence check of the call line
        bridge.stop()
        log("AudioBridge: ${frames.get()} frames sent, info: $info")
        assertTrue("too few frames: ${frames.get()}", frames.get() > 150)
        assertTrue("no route info: $info", info.any { it.startsWith("route=") })
    }
}
