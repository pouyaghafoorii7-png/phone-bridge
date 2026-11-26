package com.phonebridge.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import org.json.JSONObject

class CallReceiver : BroadcastReceiver() {
    companion object {
        private var last = TelephonyManager.EXTRA_STATE_IDLE
        private var lastNumber: String? = null
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        // Android sends each state twice (with and without the number). Report once, with the number.
        if (number == null && state == TelephonyManager.EXTRA_STATE_RINGING) return
        if (state == last) return
        val mapped = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> if (last == TelephonyManager.EXTRA_STATE_RINGING) "active" else "dialing"
            else -> "ended"
        }
        last = state
        if (number != null) lastNumber = number
        val n = number ?: lastNumber
        BridgeService.emit(ctx, JSONObject()
            .put("t", "call")
            .put("state", mapped)
            .put("number", n ?: JSONObject.NULL)
            .put("name", Phone.contactName(ctx, n) ?: JSONObject.NULL))
        BridgeService.callState(mapped)
        if (mapped == "ended") lastNumber = null
    }
}
