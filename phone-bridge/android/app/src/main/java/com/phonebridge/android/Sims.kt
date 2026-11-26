package com.phonebridge.android

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** One SIM card. The iPhone calls it a "line". [id] is Android's subscription id. */
data class Sim(val id: Int, val slot: Int, val name: String, val carrier: String, val number: String)

/**
 * Dual SIM, the way the iPhone does it: every call and message knows its line, and the iPhone says
 * which line to call or send from. This file reads the SIMs and maps broadcasts, the call log and the
 * SMS database back to a SIM. Everything is best effort: phones differ, so a failure means "unknown
 * line" (the default one is used), never a crash.
 */
object Sims {
    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** The active SIMs, in slot order. Empty without the phone permission. */
    @SuppressLint("MissingPermission")
    fun active(ctx: Context): List<Sim> {
        if (!has(ctx, android.Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val list: List<SubscriptionInfo> = try { sm.activeSubscriptionInfoList } catch (_: Throwable) { null } ?: return emptyList()
        return list.sortedBy { it.simSlotIndex }.map { info ->
            val carrier = info.carrierName?.toString()?.trim().orEmpty()
            val name = info.displayName?.toString()?.trim().orEmpty().ifEmpty { carrier }.ifEmpty { "SIM ${info.simSlotIndex + 1}" }
            Sim(info.subscriptionId, info.simSlotIndex, name, carrier, number(ctx, sm, info))
        }
    }

    @SuppressLint("MissingPermission")
    private fun number(ctx: Context, sm: SubscriptionManager, info: SubscriptionInfo): String {
        var n = ""
        if (Build.VERSION.SDK_INT >= 33) n = try { sm.getPhoneNumber(info.subscriptionId) } catch (_: Throwable) { "" }
        if (n.isBlank()) n = legacyNumber(info)
        return n
    }

    @Suppress("DEPRECATION")
    private fun legacyNumber(info: SubscriptionInfo): String = try { info.number ?: "" } catch (_: Throwable) { "" }

    /** The phone's default voice / SMS SIM, or null when it asks every time. */
    fun defaultVoice(): Int? = try { SubscriptionManager.getDefaultVoiceSubscriptionId().takeIf { it > 0 } } catch (_: Throwable) { null }
    fun defaultSms(): Int? = try { SubscriptionManager.getDefaultSmsSubscriptionId().takeIf { it > 0 } } catch (_: Throwable) { null }

    /** The `sims` message for the iPhone. */
    fun toJson(ctx: Context): JSONObject {
        val items = JSONArray()
        active(ctx).forEach {
            items.put(JSONObject().put("id", it.id).put("slot", it.slot).put("name", it.name)
                .put("carrier", it.carrier).put("number", it.number))
        }
        return JSONObject().put("t", "sims").put("items", items)
            .put("call", defaultVoice() ?: JSONObject.NULL).put("sms", defaultSms() ?: JSONObject.NULL)
    }

    /** The SIM a PHONE_STATE or SMS_RECEIVED broadcast came from (it carries the id or the slot). */
    fun fromIntent(ctx: Context, intent: Intent): Int? {
        val sims = active(ctx)
        if (sims.isEmpty()) return null
        for (key in listOf("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription", "android.telephony.extra.SUBSCRIPTION_ID")) {
            val v = intent.getIntExtra(key, -1)
            if (v > 0 && sims.any { it.id == v }) return v
        }
        for (key in listOf("android.telephony.extra.SLOT_INDEX", "slot", "phone")) {
            val s = intent.getIntExtra(key, -1)
            if (s >= 0) sims.firstOrNull { it.slot == s }?.let { return it.id }
        }
        return sims.singleOrNull()?.id
    }

    /** The subscription id behind a Telecom account, or null. */
    @SuppressLint("MissingPermission")
    fun subOfAccount(ctx: Context, h: PhoneAccountHandle): Int? {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return null
        val t = try { tm.createForPhoneAccountHandle(h) } catch (_: Throwable) { null } ?: return null
        val id = try {
            if (Build.VERSION.SDK_INT >= 30) t.subscriptionId
            else TelephonyManager::class.java.getMethod("getSubscriptionId").invoke(t) as Int
        } catch (_: Throwable) { return null }
        return id.takeIf { it > 0 }
    }

    /** The account to place a call with, so Android uses this SIM instead of asking. */
    @SuppressLint("MissingPermission")
    fun phoneAccount(ctx: Context, sub: Int): PhoneAccountHandle? {
        val tel = ctx.getSystemService(TelecomManager::class.java) ?: return null
        val handles = try { tel.callCapablePhoneAccounts } catch (_: Throwable) { return null }
        handles.firstOrNull { subOfAccount(ctx, it) == sub }?.let { return it }
        // Last resort: the accounts are listed in slot order.
        val sims = active(ctx)
        if (handles.size == sims.size) {
            val i = sims.indexOfFirst { it.id == sub }
            if (i >= 0) return handles[i]
        }
        return null
    }

    /** Maps the call log's account (component + id) to a SIM. Keeps answers for the whole batch. */
    class AccountMap(private val ctx: Context) {
        private val sims = active(ctx)
        private val cache = HashMap<String, Int?>()

        fun sub(component: String?, id: String?): Int? {
            if (id.isNullOrBlank() || sims.isEmpty()) return null
            val key = "$component|$id"
            if (cache.containsKey(key)) return cache[key]
            val r = resolve(component, id)
            cache[key] = r
            return r
        }

        private fun resolve(component: String?, id: String): Int? {
            id.toIntOrNull()?.let { n -> sims.firstOrNull { it.id == n }?.let { return it.id } }
            val cn = component?.let { ComponentName.unflattenFromString(it) }
            if (cn != null) {
                subOfAccount(ctx, PhoneAccountHandle(cn, id))?.takeIf { s -> sims.any { it.id == s } }?.let { return it }
            }
            return sims.singleOrNull()?.id
        }

        /** A subscription id from the SMS database, when it is one of the active SIMs. */
        fun known(sub: Int): Int? = sub.takeIf { s -> s > 0 && sims.any { it.id == s } }
    }

    /**
     * Calls [onChange] when a SIM is inserted, removed or changed. Returns a function that stops it,
     * or null when the phone does not allow it.
     */
    @SuppressLint("MissingPermission")
    fun watch(ctx: Context, onChange: () -> Unit): (() -> Unit)? {
        if (!has(ctx, android.Manifest.permission.READ_PHONE_STATE)) return null
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return null
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() = onChange()
        }
        return try {
            sm.addOnSubscriptionsChangedListener(ctx.mainExecutor, listener)
            val stop: () -> Unit = { try { sm.removeOnSubscriptionsChangedListener(listener) } catch (_: Throwable) {} }
            stop
        } catch (_: Throwable) { null }
    }
}
