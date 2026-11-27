package com.phonebridge.android

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Takes screenshots of the real screen on the emulator (light and dark, a fresh phone and a phone
 * that is set up, the pairing guide, the QR code), so the design can be looked at without a phone.
 * The files go to /data/local/tmp/ghafoorii-shots; tests/android_emulator.sh pulls them. Nothing here
 * checks how the screen looks: it only has to run and make the pictures.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val dev = UiDevice.getInstance(inst)
    private val dir = "/data/local/tmp/ghafoorii-shots"
    private val pkg = "com.phonebridge.android"

    private fun log(msg: String) = Log.i("PB-TEST", msg)
    private fun sh(cmd: String): String = try { dev.executeShellCommand(cmd) } catch (e: Throwable) { "error: ${e.message}" }

    private fun shot(name: String) {
        Thread.sleep(1000)
        sh("screencap -p $dir/$name.png")
        log("screenshot $name")
    }

    private fun launch(): Activity {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = inst.startActivitySync(i)
        Thread.sleep(1500)
        return a
    }

    /** Calls a private method of the activity (a sheet), on the main thread. */
    private fun call(a: Activity, name: String, vararg args: Any?) {
        val m = a.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
        m.isAccessible = true
        inst.runOnMainSync { m.invoke(a, *args) }
    }

    private fun scrollDown() {
        val w = dev.displayWidth
        val h = dev.displayHeight
        dev.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.15).toInt(), 25)
        Thread.sleep(500)
    }

    private fun setUp() {
        // Everything allowed, battery free, microphone backup on, an iPhone linked: a finished setup.
        listOf(
            "RECEIVE_SMS", "READ_SMS", "SEND_SMS", "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "READ_CALL_LOG",
            "CALL_PHONE", "ANSWER_PHONE_CALLS", "READ_CONTACTS", "RECORD_AUDIO", "POST_NOTIFICATIONS",
            "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
        ).forEach { sh("pm grant $pkg android.permission.$it") }
        sh("dumpsys deviceidle whitelist +$pkg")
        sh("settings put secure enabled_accessibility_services $pkg/$pkg.CallAudioAccess")
        sh("settings put secure accessibility_enabled 1")
        Prefs.save(ctx, "", BridgeCrypto.newKey(), null)
    }

    @Test
    fun takeScreens() {
        sh("rm -rf $dir"); sh("mkdir -p $dir")
        sh("cmd uimode night no")

        // 1. A fresh phone, light.
        var a = launch()
        shot("01-light-top")
        scrollDown()
        shot("02-light-bottom")
        dev.findObject(By.text("Advanced"))?.click()
        Thread.sleep(600)
        scrollDown()
        shot("03-light-advanced")
        a = launch()
        call(a, "showPairGuide", null)
        shot("04-light-pair-guide")
        dev.pressBack()

        // 2. A finished setup, with the bridge started.
        setUp()
        a = launch()
        shot("05-light-ready")
        try {
            BridgeService.start(ctx)
            Thread.sleep(2500)
            a = launch()
            shot("06-light-running")
            BridgeService.stop(ctx)
        } catch (e: Throwable) { log("bridge did not start: ${e.message}") }
        a = launch()
        call(a, "showQr")
        Thread.sleep(1200)
        shot("07-light-qr")
        a = launch()
        call(a, "showManualPair")
        shot("12-light-manual-pair")
        dev.pressBack()
        a = launch()
        call(a, "showWirelessOff")
        shot("13-light-wireless-off")
        dev.pressBack()

        // 3. Dark.
        sh("cmd uimode night yes")
        Thread.sleep(1500)
        a = launch()
        shot("08-dark-top")
        scrollDown()
        shot("09-dark-bottom")
        a = launch()
        call(a, "showPairGuide", null)
        shot("10-dark-pair-guide")
        dev.pressBack()
        a = launch()
        call(a, "showLineLog")
        shot("11-dark-log")
        dev.pressBack()
        sh("cmd uimode night no")

        val files = sh("ls $dir")
        log("screenshots: " + files.trim().replace("\n", " "))
        assertTrue("no screenshots were made", files.contains(".png"))
    }
}
