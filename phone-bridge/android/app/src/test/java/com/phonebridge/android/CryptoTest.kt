package com.phonebridge.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class CryptoTest {
    @Test
    fun handshakeAndReplay() {
        val k = BridgeCrypto.newKey()
        val a = BridgeCrypto(k, "a")
        val i = BridgeCrypto(k, "i")
        val ha = i.open(a.startHandshake())!!
        val hi = a.open(i.startHandshake())!!
        val ra = a.onHello(hi)!!
        val ri = i.onHello(ha)!!
        assertTrue(i.open(ra)!!.getBoolean("r"))
        assertTrue(a.open(ri)!!.getBoolean("r"))
        val f = a.seal(JSONObject().put("t", "x"))
        assertTrue(i.open(f)!!.getBoolean("_s"))
        assertNull("replay", i.open(f))
        assertNull("reflection", a.open(a.seal(JSONObject().put("t", "y"))))
    }

    /** Cross-language check against the Python reference (tests/crypto_vector.py). */
    @Test
    fun pythonVector() {
        val vecPath = System.getenv("PB_VECTOR") ?: ""
        if (vecPath.isEmpty()) return
        val v = JSONObject(File(vecPath).readText())
        val c = BridgeCrypto(Base64.getDecoder().decode(v.getString("key")), v.getString("test_role"))
        assertEquals(v.getString("room"), c.room)
        c.useEphemeral(hexBytes(v.getString("test_priv")))
        val hello = c.open(v.getString("hello"))!!
        val reply = c.onHello(hello)
        assertNotNull(reply)
        assertEquals(v.getString("body"), c.open(v.getString("session_frame"))!!.getString("body"))
        assertEquals(v.getString("body"), c.open(v.getString("static_frame"))!!.getString("body"))
        val samples = v.getJSONArray("samples").let { a -> ShortArray(a.length()) { a.getInt(it).toShort() } }
        val mu = MuLaw.encode(samples)
        val out = JSONObject()
            .put("hello", reply)
            .put("session_frame", c.seal(JSONObject().put("t", "sms").put("body", v.getString("body"))))
            .put("static_frame", c.seal(JSONObject().put("t", "sms").put("body", v.getString("body")), false))
            .put("mulaw", BridgeCrypto.hex(mu))
            .put("decoded", JSONArray(MuLaw.decode(mu).map { it.toInt() }))
            .put("ble_uuid", c.bleUuid(v.getString("day")).toString().uppercase())
        File(System.getenv("PB_REPLY")!!).writeText(out.toString())
    }

    private fun hexBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
