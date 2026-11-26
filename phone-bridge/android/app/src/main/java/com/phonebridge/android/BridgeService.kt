package com.phonebridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Keeps the encrypted link to the relay open and runs commands that come from the iPhone. */
class BridgeService : Service() {

    companion object {
        @Volatile var status: String = "Stopped"
            private set
        @Volatile private var instance: BridgeService? = null
        private val pending = ConcurrentLinkedQueue<JSONObject>()

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, BridgeService::class.java))
            } catch (e: Exception) {
                status = "Cannot start in background: turn off battery optimisation"
            }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, BridgeService::class.java))

        /** Send an event to the iPhone. Starts the service if needed. */
        fun emit(ctx: Context, event: JSONObject) {
            val s = instance
            if (s != null) s.send(event) else {
                pending.add(event)
                start(ctx)
            }
        }

        /** Phone state from [CallReceiver]: ringing | dialing | active | ended. */
        fun callState(state: String) {
            instance?.let { s -> s.main.post { s.onCallState(state) } }
        }
    }

    val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var crypto: BridgeCrypto? = null
    @Volatile private var open = false
    private var stopped = false
    private var retry = 0
    private var peerOnline = false
    private var audioWanted = false
    private var audio: AudioBridge? = null
    private var bleCrypto: BridgeCrypto? = null
    private var ble: BleLink? = null
    @Volatile private var bleUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bridge", "Bridge", NotificationManager.IMPORTANCE_LOW))
        goForeground(withMic = true)
        instance = this
        CallTap.prepare(this) // start the Shizuku call line service early, if Shizuku is on
        simWatch = Sims.watch(this) { main.post { send(Sims.toJson(this)) } } // SIM inserted / removed
        startBluetooth()
        connect()
    }

    /** Direct Bluetooth link: used whenever the iPhone is near, without the server. */
    private fun startBluetooth() {
        val key = Prefs.key(this) ?: return
        val c = BridgeCrypto(key, "a").also { bleCrypto = it }
        val link = BleLink(this, c,
            onOpen = {
                bleUp = true
                ble?.send(c.startHandshake())
                main.post { updateStatus() }
            },
            onFrame = { text -> processFrame(text, c) { ble?.send(it) } },
            onClosed = {
                bleUp = false
                c.dropSession()
                main.post { updateStatus() }
            })
        ble = link
        if (!link.start()) ble = null
        fun tick() {
            ble?.refreshDay()
            main.postDelayed({ tick() }, 10 * 60 * 1000L)
        }
        tick()
    }

    private var relayText = "Connecting…"

    private fun updateStatus() {
        val bt = when {
            ble == null -> "Bluetooth off"
            bleUp -> "Bluetooth: iPhone connected"
            else -> "Bluetooth: waiting"
        }
        setStatus("$relayText · $bt")
    }

    /** The microphone type is needed to capture call audio; Android may refuse it from the background. */
    private fun goForeground(withMic: Boolean) {
        val n = notification("Connecting…")
        if (Build.VERSION.SDK_INT >= 34) {
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            try {
                startForeground(1, n, if (withMic) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else base)
            } catch (e: Exception) {
                startForeground(1, n, base)
            }
        } else {
            startForeground(1, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private var simWatch: (() -> Unit)? = null

    override fun onDestroy() {
        simWatch?.invoke()
        simWatch = null
        stopped = true
        instance = null
        audio?.stop()
        audio = null
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        ble?.stop()
        status = "Stopped"
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "bridge")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Ghafoorii")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun setStatus(text: String) {
        status = text
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    private fun connect() {
        val url = Prefs.url(this)
        val key = Prefs.key(this)
        if (key == null) {
            setStatus("Not paired")
            stopSelf()
            return
        }
        if (url.isNullOrBlank()) {
            relayText = "Bluetooth only"
            updateStatus()
            return
        }
        val c = BridgeCrypto(key, "a").also { crypto = it }
        setStatus("Connecting…")
        val request = try { Request.Builder().url(url).build() } catch (e: Exception) {
            setStatus("Bad server address"); return
        }
        val client: OkHttpClient = Tls.client(Prefs.pin(this)).newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("join", c.room).put("role", "a").toString())
                open = true
                retry = 0
                main.post { relayText = "Server: iPhone offline"; updateStatus() }
                flush()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text == "#peer:1" -> {
                        webSocket.send(c.startHandshake()) // new forward-secret session for this connection
                        main.post { peerOnline = true; relayText = "Server: iPhone online"; updateStatus() }
                    }
                    text == "#peer:0" -> {
                        c.dropSession()
                        main.post { peerOnline = false; relayText = "Server: iPhone offline"; updateStatus() }
                    }
                    text.startsWith("#") -> {}
                    else -> processFrame(text, c) { webSocket.send(it) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (t is javax.net.ssl.SSLException) main.post { setStatus("Server certificate changed! Not connecting.") }
                lost(webSocket)
            }
        })
    }

    /** One encrypted frame from either link (server or Bluetooth). */
    private fun processFrame(text: String, c: BridgeCrypto, sendRaw: (String) -> Unit) {
        val msg = c.open(text) ?: return
        when {
            msg.optString("t") == "hello" -> {
                c.onHello(msg)?.let { sendRaw(it) }
                flush()
            }
            msg.optString("t") == "au" && msg.optBoolean("_s") ->
                audio?.play(Base64.getDecoder().decode(msg.optString("d")))
            // Commands are accepted only under the forward-secret session key.
            msg.optBoolean("_s") -> main.post { handle(msg) }
        }
    }

    private fun lost(socket: WebSocket) {
        main.post {
            if (socket !== ws) return@post
            open = false
            ws = null
            crypto?.dropSession()
            if (stopped) return@post
            val delay = minOf(60L, 1L shl minOf(retry++, 6))
            if (!status.startsWith("Server certificate")) { relayText = "Server: retry in ${delay}s"; updateStatus() }
            main.postDelayed({ if (!stopped && ws == null) connect() }, delay * 1000)
        }
    }

    private fun flush() {
        val waiting = generateSequence { pending.poll() }.toList() // snapshot: send() may queue again
        waiting.forEach { send(it) }
    }

    fun send(event: JSONObject) {
        if (event.optString("t") == "call" && event.optString("state") == "ended") {
            main.postDelayed({ send(JSONObject().put("t", "calls_batch").put("items", Phone.recentCalls(this))) }, 2500)
        }
        // Bluetooth first when the iPhone is near.
        val bc = bleCrypto
        val link = ble
        if (bleUp && bc != null && bc.hasSession && link != null && link.send(bc.seal(event))) return
        val s = ws
        val c = crypto
        if (!open || s == null || c == null) {
            pending.add(event)
            return
        }
        if (!s.send(c.seal(event))) pending.add(event)
    }

    private fun sendAudio(frame: ByteArray) {
        val event = JSONObject().put("t", "au").put("d", BridgeCrypto.b64(frame))
        val bc = bleCrypto
        if (bleUp && bc != null && bc.hasSession && ble?.send(bc.seal(event)) == true) return
        val c = crypto ?: return
        if (!c.hasSession) return
        ws?.send(c.seal(event))
    }

    private fun onCallState(state: String) {
        when (state) {
            "active", "dialing" -> if (audioWanted && audio == null) startAudio()
            "ended" -> stopAudio()
        }
    }

    private fun startAudio() {
        CallTap.prepare(this)
        if (!CallTap.ready() && !CallAudioAccess.enabled(this)) {
            send(JSONObject().put("t", "audio_error").put("why", "accessibility"))
        }
        goForeground(withMic = true)
        audio = AudioBridge(this, { sendAudio(it) }) { kind, detail ->
            main.post { send(JSONObject().put("t", "audio_info").put("kind", kind).put("detail", detail)) }
        }.also { it.start() }
        send(JSONObject().put("t", "audio_start"))
    }

    private fun stopAudio() {
        audioWanted = false
        audio?.stop()
        audio = null
    }

    /** The SIM a command asks for ("sim": subscription id), or null for the phone's default. */
    private fun simOf(cmd: JSONObject): Int? =
        if (cmd.has("sim") && !cmd.isNull("sim")) cmd.optInt("sim", -1).takeIf { it > 0 } else null

    /** Commands from the iPhone. Only frames under the session key reach this point. */
    private fun handle(cmd: JSONObject) {
        when (cmd.optString("t")) {
            "sims_sync" -> send(Sims.toJson(this))
            "sync" -> {
                send(Sims.toJson(this))
                send(JSONObject().put("t", "contacts_batch").put("items", Phone.contacts(this)))
                send(JSONObject().put("t", "calls_batch").put("items", Phone.recentCalls(this)))
                send(JSONObject().put("t", "sms_batch").put("items", Phone.recentSms(this)))
            }
            "calls_sync" -> send(JSONObject().put("t", "calls_batch").put("items", Phone.recentCalls(this)))
            "send_sms" -> {
                val ok = Phone.sendSms(this, cmd.optString("to"), cmd.optString("body"), simOf(cmd))
                send(JSONObject().put("t", "sms_status").put("id", cmd.optString("id")).put("ok", ok))
            }
            "answer" -> {
                audioWanted = cmd.optBoolean("audio")
                Phone.answer(this)
            }
            "hangup" -> {
                Phone.hangup(this)
                stopAudio()
            }
            "dtmf" -> audio?.playTone(cmd.optString("digit"))
            "dial" -> {
                val number = cmd.optString("number")
                audioWanted = cmd.optBoolean("audio")
                val sim = simOf(cmd)
                if (Phone.dial(this, number, sim)) {
                    send(JSONObject().put("t", "call").put("state", "dialing")
                        .put("number", number).put("name", Phone.contactName(this, number) ?: JSONObject.NULL)
                        .put("sim", sim ?: JSONObject.NULL))
                }
            }
            "forward" -> {
                val ok = Phone.forward(this, cmd.optString("mode"), cmd.optString("number"), simOf(cmd))
                send(JSONObject().put("t", "forward_status").put("mode", cmd.optString("mode")).put("ok", ok))
            }
        }
    }
}
