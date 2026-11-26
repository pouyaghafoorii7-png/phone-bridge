package com.phonebridge.android

import android.content.Context
import android.util.Base64
import org.json.JSONObject

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun url(ctx: Context): String? = sp(ctx).getString("url", null)
    fun key(ctx: Context): ByteArray? =
        sp(ctx).getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun pin(ctx: Context): String? = sp(ctx).getString("pin", null)

    fun save(ctx: Context, url: String, key: ByteArray, pin: String?) {
        sp(ctx).edit()
            .putString("pin", pin)
            .putString("url", url)
            .putString("key", Base64.encodeToString(key, Base64.NO_WRAP))
            .apply()
    }

    /** Text inside the pairing QR code. Whoever sees it can control this phone: keep it private. */
    fun pairingText(ctx: Context): String? {
        val u = url(ctx) ?: return null
        val k = key(ctx) ?: return null
        val o = JSONObject().put("u", u).put("k", Base64.encodeToString(k, Base64.NO_WRAP))
        pin(ctx)?.let { o.put("p", it) }
        return o.toString()
    }
}
