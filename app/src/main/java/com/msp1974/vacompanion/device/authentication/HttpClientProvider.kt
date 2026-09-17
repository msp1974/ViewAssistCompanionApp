package com.msp1974.vacompanion.device.authentication

import android.annotation.SuppressLint
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Builds the shared Ktor [HttpClient] used to talk to the user's Home Assistant instance
 * (auth token exchange, custom file/wake-word downloads) and other HTTPS endpoints.
 *
 * Uses the Android engine rather than CIO: CIO's TLS implementation always verifies the
 * server hostname against the certificate's CN/SAN with no way to opt out, so a self-signed
 * certificate that doesn't carry a matching SAN (the common case for HA installs) fails even
 * with a trust-all X509TrustManager installed. The Android engine drives connections through
 * HttpsURLConnection, letting us bypass both chain and hostname validation per-connection -
 * and only when [ignoreSslErrors] says the user has actually opted in, matching the same
 * ignoreSSLErrors gate the kiosk WebView uses (see CustomWebViewClient.onReceivedSslError).
 * When it hasn't been opted into, platform defaults are left untouched so certificates are
 * verified normally and a bad cert fails with a real, descriptive SSLException rather than
 * silently connecting or silently failing.
 *
 * [ignoreSslErrors] is a supplier (not a plain Boolean) so callers can back it with a live
 * config value - the setting can change at runtime (e.g. a user accepting the WebView's SSL
 * warning) and each request should see the current value, not the one at client construction.
 */
class HttpClientProvider(private val ignoreSslErrors: () -> Boolean = { false }) {
    fun get(): HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(HttpRequestRetry) { // Should be installed before HttpTimeout
            maxRetries = 3
            retryIf { request, response ->
                !response.status.isSuccess() && (response.status.value != 400)
            }
            retryOnException(retryOnTimeout = true)
            exponentialDelay()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5000
            socketTimeoutMillis = 5000
        }
        engine {
            sslManager = { connection ->
                if (ignoreSslErrors()) {
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf(TrustAllX509TrustManager()), SecureRandom())
                    connection.sslSocketFactory = sslContext.socketFactory
                    connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
            }
        }
    }
}

@SuppressLint("CustomX509TrustManager")
class TrustAllX509TrustManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<out X509Certificate?> = arrayOfNulls(0)

    @SuppressLint("TrustAllX509TrustManager")
    override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}

    @SuppressLint("TrustAllX509TrustManager")
    override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
}
