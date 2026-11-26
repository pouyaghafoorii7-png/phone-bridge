package com.phonebridge.android

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS for wss:// with certificate pinning. The relay uses its own self-signed certificate;
 * its SHA-256 fingerprint is learned once when the pairing is created and travels in the QR code,
 * so both phones refuse any other certificate (no man-in-the-middle, even with a rogue CA).
 */
object Tls {
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class PinningTrust(private val pin: String?, private val learned: (String) -> Unit) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certs not used")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val fp = fingerprint(leaf)
            if (pin == null) learned(fp) else if (!fp.equals(pin, ignoreCase = true)) {
                throw CertificateException("certificate fingerprint changed")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Client that accepts only the pinned certificate (or, when [pin] is null, learns it). */
    fun client(pin: String?, learned: (String) -> Unit = {}): OkHttpClient {
        val trust = PinningTrust(pin, learned)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true } // identity comes from the pinned certificate, not the name
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Connects once to a wss:// relay and returns its certificate fingerprint (blocking; not on the main thread). */
    fun learnPin(wsUrl: String): String? {
        if (!wsUrl.startsWith("wss://")) return null
        var fp: String? = null
        val httpsUrl = "https://" + wsUrl.removePrefix("wss://")
        try {
            client(null) { fp = it }.newCall(Request.Builder().url(httpsUrl).build()).execute().close()
        } catch (e: Exception) {
            // The relay answers plain HTTPS with an error; the TLS handshake already gave us the certificate.
        }
        return fp
    }
}
