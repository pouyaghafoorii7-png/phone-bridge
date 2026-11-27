package com.phonebridge.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The one screen of the Android app. Built from plain Views (see Ui.kt): a status card at the top, a
 * short setup checklist that shows what is still missing, the server address, the pairing QR code and
 * an "Advanced" part. Everything refreshes once a second while the screen is open.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val sheets = Handler(Looper.getMainLooper()) // live text inside sheets; kept while the app is paused
    private lateinit var k: Kit
    private lateinit var snack: Snack
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    // status card
    private lateinit var heroDot: PulseDot
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroChips: Flow
    private lateinit var heroBtn: Kit.Button
    private var chipSig = ""
    private var bridgeOn = false

    // set up
    private lateinit var setupPill: TextView
    private lateinit var setupBar: Bar
    private lateinit var rowPerm: Kit.Row
    private lateinit var rowBatt: Kit.Row
    private lateinit var rowLine: Kit.Row
    private lateinit var rowMic: Kit.Row
    private lateinit var rowLink: Kit.Row

    // connection, QR, advanced
    private lateinit var url: EditText
    private lateinit var connNote: TextView
    private lateinit var qrCard: LinearLayout
    private lateinit var qr: ImageView
    private lateinit var advBody: LinearLayout
    private lateinit var rowAdv: Kit.Row
    private var advOpen = false

    private val permissions = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_PHONE_NUMBERS)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    // ------------------------------------------------------------------------------------------
    //  Building the screen
    // ------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        k = Kit(this)
        list = k.vbox().apply { setPadding(dp(16), dp(8), dp(16), dp(32)) }
        val parts = listOf(header(), hero(), setupCard(), connectionCard(), qrCard(), advancedCard(), footer())
        parts.forEachIndexed { i, v -> list.add(v, MATCH, t = if (i == 0) 0 else 16) }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, MATCH, WRAP)
        }
        val root = FrameLayout(this).apply { addView(scroll, MATCH, MATCH) }
        snack = Snack(k, root)
        setContentView(root)
        k.stagger(parts.filter { it.visibility == View.VISIBLE })
    }

    private fun header(): View {
        val row = k.hbox().apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(13).toFloat())
                }
            }
        }
        row.add(logo, dp(46), dp(46))
        val col = k.vbox()
        col.add(k.text("Ghafoorii", 25f, k.c(R.color.text), Kit.Weight.SEMIBOLD).apply { letterSpacing = -0.015f })
        col.add(k.text("Phone bridge · this phone has the SIM", 13f, k.c(R.color.text2)), t = 3)
        row.add(col, l = 12)
        return row
    }

    private fun hero(): View {
        val card = k.vbox().apply {
            background = HeroDrawable(this@MainActivity)
            clipToOutline = true
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val eyebrow = k.hbox()
        heroDot = PulseDot(this)
        eyebrow.add(heroDot, dp(22), dp(22), l = -6)
        eyebrow.add(k.text("BRIDGE", 11f, 0xFFC9C2B2.toInt(), Kit.Weight.SEMIBOLD).apply { letterSpacing = 0.14f }, l = 2)
        card.add(eyebrow, MATCH)
        heroTitle = k.text("", 28f, Color.WHITE, Kit.Weight.SEMIBOLD).apply { letterSpacing = -0.015f }
        card.add(heroTitle, t = 8)
        heroSub = k.text("", 14.5f, 0xFFD6CFBF.toInt()).apply { setLineSpacing(0f, 1.12f) }
        card.add(heroSub, t = 4)
        heroChips = Flow(this, dp(8), dp(8))
        card.add(heroChips, MATCH, t = 16)
        heroBtn = k.Button()
        heroBtn.root.setOnClickListener { toggleBridge() }
        card.add(heroBtn.root, MATCH, dp(54), t = 18)
        return card
    }

    private fun setupCard(): View {
        val card = k.card()
        val head = k.hbox().apply { setPadding(dp(18), dp(18), dp(18), dp(2)) }
        head.add(k.text("Set up", 18f, k.c(R.color.text), Kit.Weight.SEMIBOLD), 0, WRAP, 1f)
        setupPill = k.pill("", k.c(R.color.amber_ink), k.c(R.color.amber_soft))
        head.add(setupPill)
        card.add(head, MATCH)
        setupBar = Bar(this)
        card.add(setupBar, MATCH, dp(5), l = 18, t = 12, r = 18, b = 8)

        rowPerm = k.Row(); rowBatt = k.Row(); rowLine = k.Row(); rowMic = k.Row(); rowLink = k.Row()
        listOf(rowPerm, rowBatt, rowLine, rowMic, rowLink).forEachIndexed { i, r ->
            card.add(r.root, MATCH)
            if (i < 4) card.addDivider()
        }
        rowPerm.root.setOnClickListener {
            if (missingGroups().isEmpty()) openAppSettings() else requestPermissions(permissions, 1)
        }
        rowBatt.root.setOnClickListener { if (batteryOk()) openAppSettings() else askBatteryExemption() }
        rowLine.root.setOnClickListener { onLineRow() }
        rowMic.root.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        rowLink.root.setOnClickListener { if (Prefs.key(this) != null) toggleQr() else createPairing() }
        return card
    }

    private fun connectionCard(): View {
        val card = k.card()
        card.add(k.text("CONNECTION", 12f, k.c(R.color.text2), Kit.Weight.SEMIBOLD).apply { letterSpacing = 0.1f }, l = 18, t = 18)
        val (box, edit) = k.field(
            "Relay server", Prefs.url(this).orEmpty(), "wss://your-server:443",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        url = edit
        card.add(box, MATCH, l = 16, t = 12, r = 16)
        connNote = k.text("Leave empty to use Bluetooth only.", 12.5f, k.c(R.color.text3)).apply { setLineSpacing(0f, 1.15f) }
        card.add(connNote, l = 20, t = 8, r = 20, b = 14)
        card.addDivider(16, 16)
        val share = k.Row()
        share.bind(Kit.Tone.QUIET, R.drawable.ic_tether, "Share internet", "Hotspot, Bluetooth or USB", chevronOn = true)
        share.root.setOnClickListener { openTethering() }
        card.add(share.root, MATCH)
        return card
    }

    private fun qrCard(): View {
        qrCard = k.card().apply {
            visibility = View.GONE
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val head = k.hbox()
        head.add(k.text("Scan with your iPhone", 18f, k.c(R.color.text), Kit.Weight.SEMIBOLD), 0, WRAP, 1f)
        head.add(k.textButton("Hide") { hideQr(true) })
        qrCard.add(head, MATCH)
        val frame = FrameLayout(this).apply {
            background = k.shape(Color.WHITE, 20)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        qr = ImageView(this).apply { adjustViewBounds = true }
        frame.addView(qr, MATCH, WRAP)
        qrCard.add(frame, MATCH, t = 12)
        qrCard.add(
            k.strip("This QR code is the only key to this phone. Show it only to your own iPhone.").apply {
                background = k.shape(k.c(R.color.bad_soft), 14)
                setTextColor(k.c(R.color.bad))
            },
            MATCH, t = 14,
        )
        return qrCard
    }

    private fun advancedCard(): View {
        val card = k.card()
        rowAdv = k.Row()
        rowAdv.bind(Kit.Tone.QUIET, R.drawable.ic_tune, "Advanced", "Call line log, Shizuku, pairing keys", chevronOn = true)
        rowAdv.chevron.setImageResource(R.drawable.ic_expand)
        rowAdv.root.setOnClickListener { toggleAdvanced() }
        card.add(rowAdv.root, MATCH)

        advBody = k.vbox().apply { visibility = View.GONE }
        fun item(icon: Int, title: String, sub: String, onClick: () -> Unit) {
            advBody.addDivider(16, 16)
            val r = k.Row()
            r.bind(Kit.Tone.QUIET, icon, title, sub, chevronOn = true)
            r.root.setOnClickListener { onClick() }
            advBody.add(r.root, MATCH)
        }
        item(R.drawable.ic_copy, "Call line log", "Copy it into a bug report when a method fails") { showLineLog() }
        item(R.drawable.ic_bolt, "Use Shizuku instead", "If the built-in way does not work on this phone") { openShizuku() }
        item(R.drawable.ic_link, "Pair the call line again", "If wireless debugging forgot this app") {
            AdbShell.forgetPairing(this)
            showPairGuide(null)
        }
        item(R.drawable.ic_refresh, "Make a new pairing key", "The old QR code stops working") {
            if (Prefs.key(this) != null) confirmNewKey() else createPairing()
        }
        card.add(advBody, MATCH)
        return card
    }

    private fun footer(): View =
        k.text("Ghafoorii ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", 12f, k.c(R.color.text3)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }

    // ------------------------------------------------------------------------------------------
    //  Live state
    // ------------------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        CallTap.prepare(this)
        autoConnect()
        tick()
    }

    private var autoTried = false

    /**
     * After a restart of the app or the phone: if this app was paired before, start the call line by
     * itself, without any window. If wireless debugging is off, the checklist row says so.
     */
    private fun autoConnect() {
        if (autoTried || Build.VERSION.SDK_INT < 31 || !AdbShell.paired(this)) return
        if (CallTap.ready() || CallTap.state() == "starting") return
        autoTried = true
        AdbShell.connect(this) { _, _ -> main.post { refresh() } }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
        hideQr(false) // do not leave the key on screen
    }

    private fun tick() {
        refresh()
        main.postDelayed({ tick() }, 1000)
    }

    private fun refresh() {
        val st = BridgeService.status
        val paired = Prefs.key(this) != null
        val missing = missingGroups()
        val batt = batteryOk()
        val lineOn = CallTap.ready()
        val micOn = CallAudioAccess.enabled(this)
        val sims = Sims.active(this)

        // ---- the status card
        val off = st == "Stopped"
        val problem = st == "Not paired" || st.startsWith("Cannot start") || st.startsWith("Server certificate")
        val connected = !off && !problem && (st.contains("iPhone online") || st.contains("iPhone connected"))
        bridgeOn = !off && !problem
        val (title, sub, dot) = when {
            st == "Not paired" -> Triple("Not linked yet", "Create the QR code below and scan it on your iPhone.", k.c(R.color.bad))
            problem -> Triple("Needs attention", st, k.c(R.color.bad))
            off -> Triple(
                "Bridge is off",
                if (paired) "Start it so your iPhone can use this phone's SIM." else "First link your iPhone: create the QR code below.",
                0xFF8B8578.toInt(),
            )
            connected -> Triple("iPhone connected", "Calls and messages are flowing.", 0xFF3DD68C.toInt())
            else -> Triple("Waiting for iPhone", st.replace(" · ", "\n"), k.c(R.color.amber))
        }
        if (heroTitle.text.toString() != title) heroTitle.text = title
        if (heroSub.text.toString() != sub) heroSub.text = sub
        heroDot.color = dot
        heroDot.pulsing = bridgeOn
        heroBtn.bind(
            if (bridgeOn) "Stop bridge" else "Start bridge",
            if (bridgeOn) R.drawable.ic_stop else R.drawable.ic_play,
            if (bridgeOn) Kit.Look.GHOST else Kit.Look.PRIMARY,
        )

        val chips = buildList {
            sims.forEach { add(R.drawable.ic_sim to "SIM ${it.slot + 1} · ${it.name}") }
            val u = Prefs.url(this@MainActivity).orEmpty()
            add(R.drawable.ic_link to if (u.isBlank()) "Bluetooth only" else "Relay · " + (Uri.parse(u).host ?: u))
            add(R.drawable.ic_call to when { lineOn -> "Sound: direct line"; micOn -> "Sound: microphone"; else -> "Sound: not set up" })
        }
        val sig = chips.joinToString("|") { "${it.first}:${it.second}" }
        if (sig != chipSig) {
            chipSig = sig
            heroChips.removeAllViews()
            chips.forEach { heroChips.addView(k.chip(it.first, it.second)) }
        }

        // ---- the checklist
        rowPerm.bind(
            if (missing.isEmpty()) Kit.Tone.DONE else Kit.Tone.TODO, R.drawable.ic_shield, "Permissions",
            if (missing.isEmpty()) "All allowed" else "Missing: " + missing.joinToString(", "),
            actionText = if (missing.isEmpty()) null else "Grant", chevronOn = missing.isEmpty(),
        )
        rowBatt.bind(
            if (batt) Kit.Tone.DONE else Kit.Tone.TODO, R.drawable.ic_battery, "Keep running in the background",
            if (batt) "Not restricted" else "Android may stop the bridge to save power",
            actionText = if (batt) null else "Allow", chevronOn = batt,
        )
        val lineTitle = "Call sound, both ways"
        val via = if (CallTap.transport() == "adb") "built-in" else "Shizuku"
        when {
            lineOn -> rowLine.bind(Kit.Tone.DONE, R.drawable.ic_call, lineTitle, "Direct call line · $via\nMethod: ${methodName()}", chevronOn = true)
            CallTap.state() == "starting" -> rowLine.bind(Kit.Tone.TODO, R.drawable.ic_call, lineTitle, "Starting the call line…")
            CallTap.running() && !CallTap.granted() ->
                rowLine.bind(Kit.Tone.TODO, R.drawable.ic_call, lineTitle, "Shizuku is running. Allow Ghafoorii in Shizuku.", actionText = "Allow")
            AdbShell.state == "error" ->
                rowLine.bind(Kit.Tone.TODO, R.drawable.ic_call, lineTitle, friendly(AdbShell.detail), actionText = "Retry")
            else -> rowLine.bind(
                Kit.Tone.TODO, R.drawable.ic_call, lineTitle,
                "Not connected. Needed to hear the caller without the speaker.", actionText = "Connect",
            )
        }
        rowMic.bind(
            if (micOn) Kit.Tone.DONE else Kit.Tone.QUIET, R.drawable.ic_mic, "Microphone backup (optional)",
            when {
                micOn -> "On. Used only when the direct line is off."
                lineOn -> "Off. Not needed while the direct line is on."
                else -> "Off. Turn it on if the direct line does not work."
            },
            actionText = if (micOn) null else "Open", chevronOn = micOn,
        )
        rowLink.bind(
            if (paired) Kit.Tone.DONE else Kit.Tone.TODO, R.drawable.ic_qr, "Link your iPhone",
            if (paired) "Linked. Show the QR code again if you need it." else "Not linked yet. Create a QR code and scan it on the iPhone.",
            actionText = if (paired) "Show QR" else "Create QR",
        )
        val done = listOf(missing.isEmpty(), batt, lineOn, paired).count { it }
        val pill = "$done of 4"
        if (setupPill.text.toString() != pill) setupPill.text = pill
        setupBar.animateTo(done / 4f)

        val pin = Prefs.pin(this)
        val note = "Leave empty to use Bluetooth only." + (if (pin != null) "\nServer pin: ${pin.take(16)}…" else "")
        if (connNote.text.toString() != note) connNote.text = note
    }

    private fun friendly(detail: String): String = when (detail) {
        AdbShell.NOT_PAIRED -> "Not paired yet. Tap Retry to pair."
        AdbShell.NO_WIRELESS -> "Wireless debugging is off. Turn it on, then tap Retry."
        else -> "Could not connect: $detail"
    }

    private fun methodName(): String {
        val forced = AudioBridge.forcedMethod(this)
        if (forced.isNotEmpty()) return LineMethods.label(forced)
        val worked = AudioBridge.workedMethod(this)
        return "Automatic" + if (worked.isNotEmpty()) " (works: ${LineMethods.label(worked)})" else ""
    }

    /** The groups of permissions that are not allowed yet, in plain words. */
    private fun missingGroups(): List<String> {
        val groups = linkedMapOf(
            "SMS" to listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS),
            "Phone" to listOf(
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
            ),
            "Contacts" to listOf(Manifest.permission.READ_CONTACTS),
            "Microphone" to listOf(Manifest.permission.RECORD_AUDIO),
            "Notifications" to (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
            "Bluetooth" to (if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else emptyList()),
        )
        return groups.filter { (_, list) -> list.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } }.keys.toList()
    }

    private fun batteryOk(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ------------------------------------------------------------------------------------------
    //  Actions
    // ------------------------------------------------------------------------------------------

    private fun toggleBridge() {
        if (bridgeOn) {
            BridgeService.stop(this)
        } else {
            if (Prefs.key(this) == null) { snack.show("Link your iPhone first: create the QR code."); return }
            BridgeService.start(this)
        }
        main.postDelayed({ refresh() }, 500)
    }

    /** Makes a new pairing key, remembers the server address and shows the QR code. */
    private fun createPairing() {
        val address = url.text.toString().trim()
        snack.show("Checking the server…", 8000)
        Thread {
            // For wss:// remember the server's certificate; it goes into the QR code as a pin.
            val pin = Tls.learnPin(address)
            main.post {
                if (address.startsWith("wss://") && pin == null) {
                    snack.show("Could not reach the server over TLS. Check the address.")
                    return@post
                }
                Prefs.save(this, address, BridgeCrypto.newKey(), pin)
                BridgeService.stop(this)
                BridgeService.start(this)
                showQr()
                refresh()
            }
        }.start()
    }

    private fun toggleQr() { if (qrCard.visibility == View.VISIBLE) hideQr(true) else showQr() }

    private fun showQr() {
        val text = Prefs.pairingText(this) ?: return
        val size = 800
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        qr.setImageBitmap(Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888))
        TransitionManager.beginDelayedTransition(list, AutoTransition().setDuration(220))
        qrCard.visibility = View.VISIBLE
        scroll.post { scroll.smoothScrollTo(0, qrCard.top - dp(12)) }
    }

    private fun hideQr(animate: Boolean) {
        if (qrCard.visibility != View.VISIBLE) return
        if (animate) TransitionManager.beginDelayedTransition(list, AutoTransition().setDuration(200))
        qrCard.visibility = View.GONE
        qr.setImageDrawable(null)
    }

    private fun toggleAdvanced() {
        advOpen = !advOpen
        TransitionManager.beginDelayedTransition(list, AutoTransition().setDuration(220))
        advBody.visibility = if (advOpen) View.VISIBLE else View.GONE
        rowAdv.chevron.animate().rotation(if (advOpen) 180f else 0f).setDuration(220).start()
        if (advOpen) scroll.post { scroll.smoothScrollTo(0, advBody.top + (advBody.parent as View).top - dp(12)) }
    }

    private fun onLineRow() {
        when {
            CallTap.ready() -> showMethods()
            CallTap.running() && !CallTap.granted() -> CallTap.requestPermission(this)
            else -> connectLine()
        }
    }

    /** Step by step: find wireless debugging, start the call line service as the shell user. */
    private fun connectLine() {
        if (CallTap.ready()) { snack.show("The direct call line is already on."); return }
        if (Build.VERSION.SDK_INT < 31) {
            snack.show("The built-in way needs Android 12 or newer. Use Shizuku (under Advanced).")
            return
        }
        AdbShell.clearError()
        if (!AdbShell.paired(this)) { showPairGuide(null); return }
        snack.show("Connecting over wireless debugging…", 12_000)
        AdbShell.connect(this) { ok, why ->
            main.post {
                when {
                    ok -> snack.show("The direct call line is on.")
                    why == AdbShell.NOT_PAIRED -> {
                        AdbShell.forgetPairing(this)
                        showPairGuide("The phone does not know this app yet. Pair it once more.")
                    }
                    why == AdbShell.NO_WIRELESS -> showWirelessOff()
                    else -> snack.show("Could not connect: $why")
                }
                refresh()
            }
        }
    }

    private fun openDeveloper() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** Shizuku runs our call line service with the shell identity too. It must be started once (wireless debugging or a computer). */
    private fun openShizuku() {
        when (CallTap.state()) {
            "denied" -> CallTap.requestPermission(this)
            "missing" -> {
                val app = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (app != null) {
                    snack.show("In Shizuku, tap Start. Then come back here and tap Connect.")
                    startActivity(app)
                } else {
                    snack.show("Install Shizuku first, start it, then come back.")
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
        if (!batteryOk()) {
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

    // ------------------------------------------------------------------------------------------
    //  Sheets
    // ------------------------------------------------------------------------------------------

    /** First time: turn on wireless debugging, then pair with a code typed into a notification. */
    private fun showPairGuide(banner: String?) {
        var status: TextView? = null
        val dlg = k.sheet("Connect the call line", "One time only. It takes about a minute.") { dlg ->
            if (banner != null) {
                add(k.strip(banner).apply { setTextColor(k.c(R.color.amber_ink)) }, MATCH, t = 14)
            }
            add(
                k.step(
                    1, "Turn on Wireless debugging",
                    "Settings → Developer options → Wireless debugging. Wi-Fi must be on, but only for this step. " +
                        "No Developer options? Settings → About phone → Software information, then tap “Build number” 7 times.",
                ),
                MATCH, t = 18,
            )
            val dev = k.Button().apply { bind("Open Developer options", null, Kit.Look.TONAL) }
            dev.root.setOnClickListener { openDeveloper() }
            add(dev.root, MATCH, dp(46), l = 42, t = 10)
            add(k.step(2, "Tap “Pair device with pairing code”", "Leave that window open."), MATCH, t = 18)
            add(
                k.step(
                    3, "Type the code in the notification",
                    "Pull down the notification bar. In the “Ghafoorii pairing” notification, tap “Enter code” and type the 6 digits.",
                ),
                MATCH, t = 18,
            )
            val s = k.strip(AdbPairing.statusLine())
            status = s
            add(s, MATCH, t = 20)
            val start = k.Button().apply { bind("Start pairing", R.drawable.ic_link, Kit.Look.PRIMARY) }
            start.root.setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                    s.text = "Allow notifications, then tap “Start pairing” again."
                } else {
                    AdbPairing.start(this@MainActivity)
                    start.bind("Restart pairing", R.drawable.ic_refresh, Kit.Look.TONAL)
                    s.text = AdbPairing.statusLine()
                }
            }
            add(start.root, MATCH, dp(54), t = 12)
            add(k.textButton("I will type the port and code here instead") { dlg.dismiss(); showManualPair() }, MATCH, t = 4)
        }
        val poll = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                status?.let { if (AdbPairing.phase != "idle") it.text = AdbPairing.statusLine() }
                if (AdbPairing.phase == "done") {
                    dlg.dismiss()
                    snack.show("The direct call line is on.")
                    refresh()
                    return
                }
                sheets.postDelayed(this, 600)
            }
        }
        sheets.post(poll)
    }

    /** Paired before, but wireless debugging is off now (it turns off after a reboot or a Wi-Fi change). */
    private fun showWirelessOff() {
        k.sheet("Turn on Wireless debugging", "Android turns it off after a restart or when Wi-Fi changes. This app is already paired.") { dlg ->
            add(
                k.step(1, "Connect to Wi-Fi", "Wireless debugging only works while the phone is on Wi-Fi. Once the call line is on, you can switch Wi-Fi off again."),
                MATCH, t = 18,
            )
            add(
                k.step(2, "Switch on Wireless debugging", "Settings → Developer options → Wireless debugging."),
                MATCH, t = 18,
            )
            val dev = k.Button().apply { bind("Open Developer options", null, Kit.Look.TONAL) }
            dev.root.setOnClickListener { openDeveloper() }
            add(dev.root, MATCH, dp(46), l = 42, t = 10)
            val retry = k.Button().apply { bind("Connect again", R.drawable.ic_refresh, Kit.Look.PRIMARY) }
            retry.root.setOnClickListener { dlg.dismiss(); connectLine() }
            add(retry.root, MATCH, dp(54), t = 20)
        }
    }

    /** The old way: type the pairing port and code by hand (needs the pairing window open next to this app). */
    private fun showManualPair() {
        k.sheet(
            "Type the port and code",
            "Use this if the notification does not work. Open Wireless debugging → “Pair device with pairing code” " +
                "in a split-screen window, so the code stays visible while you type.",
        ) { dlg ->
            val (portBox, portEdit) = k.field("Pairing port", "", "for example 37123", InputType.TYPE_CLASS_NUMBER)
            val (codeBox, codeEdit) = k.field("6-digit pairing code", "", "000000", InputType.TYPE_CLASS_NUMBER)
            add(portBox, MATCH, t = 16)
            add(codeBox, MATCH, t = 10)
            val st = k.strip("This phone pairs with itself. Nothing leaves the phone.")
            add(st, MATCH, t = 14)
            val go = k.Button().apply { bind("Pair and connect", null, Kit.Look.PRIMARY) }
            go.root.setOnClickListener {
                val p = portEdit.text.toString().trim().toIntOrNull()
                val c = codeEdit.text.toString().trim()
                if (p == null || c.length != 6) { st.text = "Type the pairing port and the 6-digit code."; return@setOnClickListener }
                st.text = "Pairing…"
                AdbShell.pair(this@MainActivity, p, c) { ok, why ->
                    main.post {
                        if (!ok) { st.text = "$why. Check the port and the code, then try again."; return@post }
                        st.text = "Paired. Starting the call line…"
                        AdbShell.connect(this@MainActivity) { ok2, why2 ->
                            main.post {
                                if (ok2) {
                                    dlg.dismiss()
                                    snack.show("The direct call line is on.")
                                    refresh()
                                } else {
                                    st.text = "Paired, but the call line did not start ($why2). Turn on Wireless debugging and tap Connect."
                                }
                            }
                        }
                    }
                }
            }
            add(go.root, MATCH, dp(54), t = 12)
        }
    }

    /** iTell's method list: Automatic, or one method by hand, named like iTell's picker. */
    private fun showMethods() {
        val all = CallTap.methods()
        if (all.isEmpty()) { snack.show("Connect the call line first, then pick a method."); return }
        val worked = AudioBridge.workedMethod(this)
        val fails = AudioBridge.failures(this)
        val forced = AudioBridge.forcedMethod(this)
        k.sheet(
            "Call line method",
            "Automatic tries the best ones for this phone, up to four per call. Or pick the one that works for you in iTell.",
        ) { dlg ->
            fun option(code: String, name: String, how: String, mark: Pair<String, Boolean>?) {
                val row = k.hbox().apply {
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                    background = k.pressable(null, 14)
                    isClickable = true
                    setOnClickListener {
                        AudioBridge.setForcedMethod(this@MainActivity, code)
                        dlg.dismiss()
                        refresh()
                    }
                }
                val on = forced == code
                val radio = FrameLayout(this@MainActivity).apply {
                    background = if (on) k.oval(k.c(R.color.amber)) else GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), k.c(R.color.text3))
                    }
                    if (on) addView(k.icon(R.drawable.ic_check, k.c(R.color.ink)), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                }
                row.add(radio, dp(24), dp(24))
                val col = k.vbox()
                col.add(k.text(name, 15.5f, k.c(R.color.text), Kit.Weight.SEMIBOLD))
                col.add(k.text(how, 13f, k.c(R.color.text2)).apply { setLineSpacing(0f, 1.12f) }, t = 2)
                row.add(col, 0, WRAP, 1f, l = 14, r = 8)
                if (mark != null) {
                    val (label, good) = mark
                    row.add(k.pill(label, k.c(if (good) R.color.ok else R.color.bad), k.c(if (good) R.color.ok_soft else R.color.bad_soft)))
                }
                add(row, MATCH, t = 2)
            }
            addSpace(10)
            option("", "Automatic", "Recommended" + if (worked in all) ". Works here: ${LineMethods.label(worked)}" else "", null)
            for (code in all) {
                val info = LineMethods.all.firstOrNull { it.code == code }
                val n = fails[code] ?: 0
                val mark = when {
                    code == worked -> "works" to true
                    n > 0 -> "failed ${n}×" to false
                    else -> null
                }
                option(code, info?.name ?: code, info?.how ?: "", mark)
            }
            add(k.textButton("Forget results") {
                AudioBridge.resetResults(this@MainActivity)
                dlg.dismiss()
                refresh()
            }, MATCH, t = 8)
        }
    }

    private fun LinearLayout.addSpace(h: Int) { add(View(context), MATCH, dp(h)) }

    /** The call-line service's own log: copy it into a bug report when a method does not work. */
    private fun showLineLog() {
        val body = CallTap.log().ifEmpty { "No call line log yet. Connect the call line and make a call." }
        val full = listOf(CallTap.info(), body).filter { it.isNotBlank() }.joinToString("\n\n")
        k.sheet("Call line log", "Copy this into a bug report when a method does not work.") { _ ->
            val tv = TextView(this@MainActivity).apply {
                text = full
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(k.c(R.color.text2))
                background = k.shape(k.c(R.color.field), 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            add(tv, MATCH, t = 14)
            val copy = k.Button().apply { bind("Copy", R.drawable.ic_copy, Kit.Look.PRIMARY) }
            copy.root.setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ghafoorii call line log", full))
                copy.bind("Copied", R.drawable.ic_check, Kit.Look.TONAL)
            }
            add(copy.root, MATCH, dp(54), t = 12)
        }
    }

    private fun confirmNewKey() {
        k.sheet("Make a new pairing key?", "The old QR code stops working. You will scan the new one on your iPhone.") { dlg ->
            val go = k.Button().apply { bind("Make a new key", R.drawable.ic_refresh, Kit.Look.PRIMARY) }
            go.root.setOnClickListener { dlg.dismiss(); createPairing() }
            add(go.root, MATCH, dp(54), t = 18)
            add(k.textButton("Cancel") { dlg.dismiss() }, MATCH, t = 4)
        }
    }
}
