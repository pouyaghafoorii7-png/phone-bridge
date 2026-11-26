package com.phonebridge.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // One SMS can arrive in several parts; join them per sender.
        parts.groupBy { it.originatingAddress ?: "" }.forEach { (from, msgs) ->
            val body = msgs.joinToString("") { it.messageBody ?: "" }
            val ts = msgs.first().timestampMillis
            BridgeService.emit(ctx, JSONObject()
                .put("t", "sms")
                .put("id", "r$ts-${from.hashCode()}")
                .put("peer", from)
                .put("name", Phone.contactName(ctx, from) ?: JSONObject.NULL)
                .put("body", body)
                .put("ts", System.currentTimeMillis() / 1000.0)
                .put("out", false))
        }
    }
}
