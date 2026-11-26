package com.phonebridge.android

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol v2 (docs/PROTOCOL.md): AES-256-GCM frames, X25519 session keys with forward secrecy,
 * replay and reflection protection. Thread-safe.
 */
class BridgeCrypto(private val pairKey: ByteArray, private val role: String) {
    val room: String = MessageDigest.getInstance("SHA-256").digest(pairKey)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }.substring(0, 32)

    private val random = SecureRandom()
    private val staticKey = Hkdf.computeHkdf("HMACSHA256", pairKey, ByteArray(0), "pb2 static".toByteArray(), 32)
    private var priv: ByteArray? = null
    private var session: ByteArray? = null
    private var sendN = 0L
    private var recvN = 0L
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 5000
    }

    val hasSession: Boolean @Synchronized get() = session != null

    /** Bluetooth service UUID for one UTC day ("YYYY-MM-DD"); it changes daily so the phone cannot be tracked. */
    fun bleUuid(day: String): java.util.UUID {
        val b = Hkdf.computeHkdf("HMACSHA256", pairKey, ByteArray(0), "pb2 ble $day".toByteArray(), 16)
        val bb = java.nio.ByteBuffer.wrap(b)
        return java.util.UUID(bb.long, bb.long)
    }

    /** New ephemeral key (drops the old session). Returns the hello frame to send. */
    @Synchronized fun startHandshake(): String {
        priv = X25519.generatePrivateKey()
        session = null
        return hello(false)
    }

    @Synchronized fun useEphemeral(privateKey: ByteArray) {
        priv = privateKey.copyOf()
        session = null
    }

    @Synchronized fun dropSession() { session = null }

    @Synchronized private fun hello(reply: Boolean): String {
        val p = priv ?: X25519.generatePrivateKey().also { priv = it }
        val pub = X25519.publicFromPrivate(p)
        return seal(JSONObject().put("t", "hello").put("e", b64(pub)).put("r", reply), false)
    }

    /** Derives the session key from the peer's hello. Returns a reply frame when one is needed. */
    @Synchronized fun onHello(msg: JSONObject): String? {
        val p = priv ?: X25519.generatePrivateKey().also { priv = it }
        val peer = Base64.getDecoder().decode(msg.getString("e"))
        val mine = X25519.publicFromPrivate(p)
        val shared = X25519.computeSharedSecret(p, peer)
        val (lo, hi) = if (compare(mine, peer) <= 0) mine to peer else peer to mine
        session = Hkdf.computeHkdf("HMACSHA256", shared, pairKey, "pb2 session".toByteArray() + lo + hi, 32)
        sendN = 0
        recvN = 0
        return if (msg.optBoolean("r")) null else hello(true)
    }

    @Synchronized fun seal(obj: JSONObject, useSession: Boolean = session != null): String {
        val o = JSONObject(obj.toString()).put("from", role).put("ts", System.currentTimeMillis() / 1000.0)
        val key: ByteArray
        val kind: Byte
        val s = session
        if (useSession && s != null) {
            o.put("n", ++sendN); key = s; kind = 1
        } else {
            o.put("mid", hex(ByteArray(8).also { random.nextBytes(it) })); key = staticKey; kind = 0
        }
        val header = byteArrayOf(2, kind)
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(header)
        return b64(header + nonce + c.doFinal(o.toString().toByteArray(Charsets.UTF_8)))
    }

    /** Returns the message, with "_s" = true when it came under the session key, or null if rejected. */
    @Synchronized fun open(text: String): JSONObject? = try {
        openOrThrow(text)
    } catch (e: Exception) {
        null // wrong key, tampered or malformed
    }

    private fun openOrThrow(text: String): JSONObject? {
        val d = Base64.getDecoder().decode(text)
        if (d.size < 30 || d[0].toInt() != 2 || (d[1].toInt() != 0 && d[1].toInt() != 1)) return null
        val fromSession = d[1].toInt() == 1
        val key = (if (fromSession) session else staticKey) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, d, 2, 12))
        c.updateAAD(d, 0, 2)
        val o = JSONObject(String(c.doFinal(d, 14, d.size - 14), Charsets.UTF_8))
        return when {
            o.optString("from") == role -> null // reflected frame
            fromSession -> {
                val n = o.optLong("n", 0)
                if (n <= recvN) null else { recvN = n; o.put("_s", true) }
            }
            else -> {
                val mid = o.optString("mid")
                val age = Math.abs(System.currentTimeMillis() / 1000.0 - o.optDouble("ts", 0.0))
                if (age > 7 * 86400 || mid.isEmpty() || seen.containsKey(mid)) null
                else { seen[mid] = true; o.put("_s", false) }
            }
        }
    }

    companion object {
        fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val x = a[i].toInt() and 0xff
                val y = b[i].toInt() and 0xff
                if (x != y) return x - y
            }
            return a.size - b.size
        }
    }
}

/** G.711 mu-law, the same as the iPhone side. */
object MuLaw {
    fun encode(samples: ShortArray): ByteArray = ByteArray(samples.size) { i ->
        var s = samples[i].toInt()
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > 32635) s = 32635
        s += 0x84
        var exp = 7
        var mask = 0x4000
        while (s and mask == 0 && exp > 0) { exp--; mask = mask shr 1 }
        ((sign or (exp shl 4) or ((s shr (exp + 3)) and 0x0F)).inv() and 0xFF).toByte()
    }

    fun decode(data: ByteArray): ShortArray = ShortArray(data.size) { i ->
        val u = data[i].toInt().inv() and 0xFF
        var s = ((((u and 0x0F) shl 3) + 0x84) shl ((u shr 4) and 7)) - 0x84
        if (u and 0x80 != 0) s = -s
        s.coerceIn(-32768, 32767).toShort()
    }
}
