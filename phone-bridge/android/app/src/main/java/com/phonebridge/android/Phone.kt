package com.phonebridge.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Everything that touches the SIM: SMS, calls, contacts. */
object Phone {
    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun contactName(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank() || !has(ctx, android.Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    fun sendSms(ctx: Context, to: String, body: String): Boolean {
        if (!has(ctx, android.Manifest.permission.SEND_SMS)) return false
        return try {
            val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            sm.sendMultipartTextMessage(to, null, sm.divideMessage(body), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Last [limit] messages, newest first. */
    fun recentSms(ctx: Context, limit: Int = 300): JSONArray {
        val out = JSONArray()
        if (!has(ctx, android.Manifest.permission.READ_SMS)) return out
        val names = HashMap<String, String?>()
        val cols = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, cols, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext() && out.length() < limit) {
                val addr = c.getString(1) ?: continue
                val type = c.getInt(4)
                if (type != Telephony.Sms.MESSAGE_TYPE_INBOX && type != Telephony.Sms.MESSAGE_TYPE_SENT) continue
                val name = names.getOrPut(addr) { contactName(ctx, addr) }
                out.put(JSONObject()
                    .put("id", "s" + c.getLong(0))
                    .put("peer", addr)
                    .put("name", name ?: JSONObject.NULL)
                    .put("body", c.getString(2) ?: "")
                    .put("ts", c.getLong(3) / 1000.0)
                    .put("out", type == Telephony.Sms.MESSAGE_TYPE_SENT))
            }
        }
        return out
    }

    /** Last [limit] calls, newest first. */
    fun recentCalls(ctx: Context, limit: Int = 200): JSONArray {
        val out = JSONArray()
        if (!has(ctx, android.Manifest.permission.READ_CALL_LOG)) return out
        val cols = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.length() < limit) {
                    val type = when (c.getInt(3)) {
                        CallLog.Calls.INCOMING_TYPE -> "in"
                        CallLog.Calls.OUTGOING_TYPE -> "out"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "rejected"
                        else -> "in"
                    }
                    out.put(JSONObject()
                        .put("id", "c" + c.getLong(0))
                        .put("number", c.getString(1) ?: "")
                        .put("name", c.getString(2) ?: JSONObject.NULL)
                        .put("type", type)
                        .put("ts", c.getLong(4) / 1000.0)
                        .put("dur", c.getLong(5)))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** All contacts with their phone numbers. */
    fun contacts(ctx: Context, limit: Int = 3000): JSONArray {
        val out = JSONArray()
        if (!has(ctx, android.Manifest.permission.READ_CONTACTS)) return out
        val byId = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext() && byId.size < limit) {
                    val name = c.getString(1) ?: continue
                    val number = c.getString(2) ?: continue
                    val entry = byId.getOrPut(c.getLong(0)) { name to mutableListOf() }
                    val digits = number.filter { it.isDigit() }
                    if (entry.second.none { it.filter { ch -> ch.isDigit() } == digits }) entry.second.add(number)
                }
            }
        } catch (e: Exception) { }
        for ((id, v) in byId) {
            out.put(JSONObject().put("id", "p$id").put("name", v.first).put("numbers", JSONArray(v.second)))
        }
        return out
    }

    /**
     * Carrier call forwarding (GSM MMI codes). The carrier sends calls for this SIM to [number],
     * so the iPhone rings natively with full-quality audio.
     */
    fun forward(ctx: Context, mode: String, number: String): Boolean {
        val n = number.filter { it.isDigit() || it == '+' }
        val code = when (mode) {
            "all" -> "**21*$n#"
            "conditional" -> "**004*$n#"
            "busy" -> "**67*$n#"
            "noanswer" -> "**61*$n#"
            "unreachable" -> "**62*$n#"
            "off" -> "##002#"
            else -> return false
        }
        if (mode != "off" && n.length < 5) return false
        return dial(ctx, code)
    }

    private fun telecom(ctx: Context) = ctx.getSystemService(TelecomManager::class.java)

    @SuppressLint("MissingPermission")
    fun answer(ctx: Context): Boolean {
        if (!has(ctx, android.Manifest.permission.ANSWER_PHONE_CALLS)) return false
        @Suppress("DEPRECATION") telecom(ctx).acceptRingingCall()
        return true
    }

    @SuppressLint("MissingPermission")
    fun hangup(ctx: Context): Boolean {
        if (!has(ctx, android.Manifest.permission.ANSWER_PHONE_CALLS)) return false
        @Suppress("DEPRECATION") return telecom(ctx).endCall()
    }

    @SuppressLint("MissingPermission")
    fun dial(ctx: Context, number: String): Boolean {
        if (!has(ctx, android.Manifest.permission.CALL_PHONE)) return false
        telecom(ctx).placeCall(Uri.fromParts("tel", number, null), Bundle())
        return true
    }
}
