package com.phonebridge.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var qr: ImageView

    private val permissions = buildList {
        add(android.Manifest.permission.RECEIVE_SMS)
        add(android.Manifest.permission.READ_SMS)
        add(android.Manifest.permission.SEND_SMS)
        add(android.Manifest.permission.READ_PHONE_STATE)
        add(android.Manifest.permission.READ_CALL_LOG)
        add(android.Manifest.permission.CALL_PHONE)
        add(android.Manifest.permission.ANSWER_PHONE_CALLS)
        add(android.Manifest.permission.READ_CONTACTS)
        add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) {
            add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        fun button(text: String, onClick: () -> Unit) =
            Button(this).apply { this.text = text; setOnClickListener { onClick() } }.also { col.addView(it) }

        col.addView(TextView(this).apply { text = "Ghafoorii"; textSize = 24f })
        status = TextView(this).apply { textSize = 16f; setPadding(0, pad / 2, 0, pad) }
        col.addView(status)

        col.addView(TextView(this).apply { text = "1. Relay server address (leave empty for Bluetooth only)" })
        val url = EditText(this).apply {
            setText(Prefs.url(this@MainActivity) ?: "wss://YOUR_SERVER_IP:443")
            setSingleLine()
        }
        col.addView(url)

        button("2. Grant permissions") { requestPermissions(permissions, 1) }
        button("3. Do not restrict battery") { askBatteryExemption() }
        button("4. Call sound to iPhone: turn on “Ghafoorii call audio”") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("4b. Clean call sound: direct call line (Shizuku)") { openShizuku() }
        lateinit var methodButton: Button
        methodButton = button(methodLabel()) {
            // Like iTell's method list: automatic, or one method by hand when automatic picks badly.
            val all = listOf("") + CallTap.methods()
            val now = AudioBridge.forcedMethod(this)
            AudioBridge.setForcedMethod(this, all[(all.indexOf(now) + 1) % all.size])
            methodButton.text = methodLabel()
        }
        button("5. Create new pairing (QR)") {
            val address = url.text.toString().trim()
            status.text = "Checking the server…"
            Thread {
                // For wss:// remember the server's certificate; it goes into the QR code as a pin.
                val pin = Tls.learnPin(address)
                main.post {
                    if (address.startsWith("wss://") && pin == null) {
                        status.text = "Could not reach the server over TLS. Check the address."
                        return@post
                    }
                    Prefs.save(this, address, BridgeCrypto.newKey(), pin)
                    BridgeService.stop(this)
                    BridgeService.start(this)
                    showQr()
                }
            }.start()
        }
        button("Show / hide pairing QR") { if (qr.visibility == View.VISIBLE) qr.visibility = View.GONE else showQr() }
        button("Share internet (hotspot / Bluetooth / USB)") { openTethering() }
        button("Start bridge") { BridgeService.start(this) }
        button("Stop bridge") { BridgeService.stop(this) }

        col.addView(TextView(this).apply {
            text = "Warning: the QR code is the only key to this phone. Show it only to your own iPhone. " +
                "Create a new pairing if someone else has seen it."
            setTextColor(Color.rgb(180, 60, 0))
            setPadding(0, pad, 0, pad)
        })
        qr = ImageView(this).apply { visibility = View.GONE; adjustViewBounds = true }
        col.addView(qr)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        CallTap.prepare(this)
        tick()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
        qr.visibility = View.GONE // do not leave the key on screen
    }

    private fun tick() {
        val missing = permissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            .map { it.substringAfterLast('.').lowercase().replace('_', ' ') }
        if (phoneInfo.isEmpty() && CallTap.ready()) phoneInfo = CallTap.info()
        status.text = "Status: ${BridgeService.status}" +
            (if (missing.isNotEmpty()) "\nMissing permissions: ${missing.joinToString()}" else "") +
            "\nCall sound to iPhone: " + when {
                CallTap.ready() -> "direct call line, both directions (Shizuku)"
                CallAudioAccess.enabled(this) -> "microphone (step 4b gives clean sound)"
                else -> "off (step 4 or 4b)"
            } + when (CallTap.state()) {
                "missing" -> ""
                "denied" -> "\nShizuku: runs, but Ghafoorii is not allowed (tap 4b)"
                "starting" -> "\nShizuku: starting the call line service…"
                else -> ""
            } +
            (if (phoneInfo.isNotEmpty()) "\nPhone: $phoneInfo" else "") +
            (Prefs.pin(this)?.let { "\nServer pin: ${it.take(16)}…" } ?: "")
        main.postDelayed({ tick() }, 1000)
    }

    private var phoneInfo = ""

    private fun methodLabel(): String {
        val forced = AudioBridge.forcedMethod(this)
        val last = AudioBridge.workedMethod(this)
        return "Call line method: " + if (forced.isNotEmpty()) forced else
            "automatic" + (if (last.isNotEmpty()) " (works: $last)" else "")
    }

    /**
     * Step 4b. Shizuku runs our call line service with the shell identity (like `adb shell`).
     * Shizuku itself is started one time with wireless debugging (Android 11+) or a computer (ADB).
     */
    private fun openShizuku() {
        when (CallTap.state()) {
            "denied" -> CallTap.requestPermission(this)
            "missing" -> {
                val app = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (app != null) {
                    status.text = "In Shizuku, tap Start (wireless debugging) or follow its ADB steps. Then come back and tap 4b again."
                    startActivity(app)
                } else {
                    status.text = "Install Shizuku first, start it, then tap 4b again."
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                    } catch (_: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
                    }
                }
            }
            else -> CallTap.prepare(this)
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    /** Android does not let apps switch the hotspot on, so open the system screen for it. */
    private fun openTethering() {
        val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        try {
            startActivity(tether)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private fun showQr() {
        val text = Prefs.pairingText(this) ?: return
        val size = 800
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        qr.setImageBitmap(bmp)
        qr.visibility = View.VISIBLE
    }
}
