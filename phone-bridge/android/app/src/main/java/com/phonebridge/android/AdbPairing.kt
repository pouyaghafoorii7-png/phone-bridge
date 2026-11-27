package com.phonebridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * First-time pairing for the built-in call line, made easy.
 *
 * Android shows the pairing code only while its "Pair device with pairing code" window is open, and
 * that window closes when you leave it. So the code is typed into a notification instead (the trick
 * Shizuku uses). The port of the pairing service is found by itself over mDNS, so the notification
 * only asks for the 6 digits. After the code is accepted the call line starts by itself.
 */
object AdbPairing {
    private const val TAG = "PB-Pairing"
    private const val CHANNEL = "pairing"
    private const val NOTE_ID = 4242
    const val KEY_CODE = "pairing_code"
    const val ACTION_REPLY = "com.phonebridge.android.PAIRING_CODE"

    /** idle | searching | ready | pairing | connecting | done | error */
    @Volatile var phase = "idle"; private set
    @Volatile var message = ""; private set
    @Volatile private var port = -1

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val giveUp = Runnable { appCtx?.let { stop(it) } }

    /** One line for the screens. */
    fun statusLine(): String = if (phase == "idle") "Tap “Start pairing”, then follow the steps." else message

    /** Starts looking for the pairing window and shows the notification that takes the code. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        stopDiscovery()
        port = -1
        set(
            app, "searching",
            "Waiting for the pairing window. In Wireless debugging, tap “Pair device with pairing code”. " +
                "If it is not found (a VPN can block it), type the port and the code in this notification, like 41231 482910.",
        )
        discover(app)
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, 5 * 60_000L)
    }

    fun stop(ctx: Context) {
        stopDiscovery()
        main.removeCallbacks(giveUp)
        phase = "idle"; message = ""
        ctx.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
    }

    /**
     * The text typed into the notification: the 6-digit code, or "port code" when the port was not
     * found by itself. Pairs, then starts the call line. [done] runs when everything is finished.
     */
    fun submit(ctx: Context, input: String, done: () -> Unit) {
        val app = ctx.applicationContext
        appCtx = app
        val nums = Regex("\\d+").findAll(input).map { it.value }.toList()
        val code = nums.firstOrNull { it.length == 6 }
        val typedPort = nums.firstOrNull { it != code && it.length in 3..5 }?.toIntOrNull()
        val usePort = typedPort ?: port
        if (code == null) {
            set(app, "error", "That is not a 6-digit code. Type the 6 digits shown in the pairing window.")
            done(); return
        }
        if (usePort <= 0) {
            set(app, "error", "The pairing window was not found. Type the port and the code, like 37123 482910.")
            done(); return
        }
        set(app, "pairing", "Pairing…")
        AdbShell.pair(app, usePort, code) { ok, why ->
            if (!ok) {
                set(app, "error", "Pairing failed. Check the code and try again. ($why)")
                done(); return@pair
            }
            set(app, "connecting", "Paired. Starting the call line…")
            AdbShell.connect(app) { ok2, why2 ->
                if (ok2) {
                    set(app, "done", "The call line is on.")
                    stopDiscovery()
                } else {
                    set(app, "error", "Paired, but the call line did not start ($why2). Open Ghafoorii and tap Connect.")
                }
                done()
            }
        }
    }

    private fun set(app: Context, p: String, m: String) {
        phase = p
        message = m
        post(app)
    }

    private fun post(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle("Ghafoorii pairing")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(true)
            .setOngoing(phase == "searching" || phase == "ready" || phase == "pairing" || phase == "connecting")
        if (phase == "done") b.setTimeoutAfter(6_000)
        if (phase == "searching" || phase == "ready" || phase == "error") {
            val reply = PendingIntent.getBroadcast(
                app, 1, Intent(app, PairReceiver::class.java).setAction(ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build()
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(app, R.drawable.ic_link), "Enter code", reply)
                    .addRemoteInput(input).build(),
            )
        }
        nm.notify(NOTE_ID, b.build())
    }

    private fun discover(app: Context) {
        val m = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery start failed: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    @Suppress("DEPRECATION")
                    m.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "resolve failed: $errorCode") }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) { onPort(app, serviceInfo.port) }
                    })
                } catch (e: Throwable) { Log.w(TAG, "resolve", e) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (phase == "ready") {
                    port = -1
                    set(app, "searching", "The pairing window closed. Tap “Pair device with pairing code” again.")
                }
            }
        }
        discovery = l
        try {
            m.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Throwable) { Log.w(TAG, "discover", e) }
    }

    private fun onPort(app: Context, p: Int) {
        port = p
        Log.i(TAG, "pairing service on port $p")
        if (phase == "searching" || phase == "error") {
            set(app, "ready", "Found it. Pull down the notification bar and type the 6-digit code in the “Ghafoorii pairing” notification.")
        }
    }

    private fun stopDiscovery() {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Throwable) {}
        discovery = null
    }
}

/** Receives the code typed into the pairing notification. */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdbPairing.ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbPairing.KEY_CODE)?.toString().orEmpty()
        val pending = goAsync()
        AdbPairing.submit(context, text) { try { pending.finish() } catch (_: Throwable) {} }
    }
}
