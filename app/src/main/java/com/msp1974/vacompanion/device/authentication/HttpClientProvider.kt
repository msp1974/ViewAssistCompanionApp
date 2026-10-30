package com.msp1974.vacompanion.device.authentication

import android.annotation.SuppressLint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds the shared Ktor [HttpClient] used to talk to the user's Home Assistant instance
 * (auth token exchange, custom file/wake-word downloads) and other HTTPS endpoints.
 *
 * Uses the OkHttp engine, which bundles its own current OkHttp rather than relying on the
 * platform's (frozen, old on older Android versions) HttpURLConnection copy, and which lets us
 * opt out of chain and hostname validation - CIO can't skip hostname verification, so a
 * self-signed certificate without a matching SAN (the common case for HA installs) fails there
 * even with a trust-all X509TrustManager. The opt-out only applies when [ignoreSslErrors] says
 * the user has actually opted in, matching the same ignoreSSLErrors gate the kiosk WebView uses
 * (see CustomWebViewClient.onReceivedSslError). Otherwise certificates are verified normally
 * against the platform trust store so a bad cert fails with a real, descriptive SSLException.
 *
 * [ignoreSslErrors] is a supplier (not a plain Boolean) so callers can back it with a live
 * config value - the setting can change at runtime (e.g. a user accepting the WebView's SSL
 * warning) and each request should see the current value, not the one at client construction.
 * It is therefore consulted per handshake by the trust manager and hostname verifier below.
 *
 * Connection attempts (DNS result, each address tried, proxy, outcome and timing) and retries
 * are logged at debug level, so a connect timeout can be traced to the route it actually took.
 */
class HttpClientProvider(private val ignoreSslErrors: () -> Boolean = { false }) {
    fun get(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(HttpRequestRetry) { // Should be installed before HttpTimeout
            maxRetries = 3
            retryIf { _, response ->
                !response.status.isSuccess() && (response.status.value != 400)
            }
            // A connect timeout means the route is dead, not flaky - retrying only multiplies the
            // wait (connect timeout x attempts) before the real error reaches the user.
            retryOnException(retryOnTimeout = false)
            exponentialDelay()
            modifyRequest { request ->
                Timber.d("HTTP retry #$retryCount for ${request.url} after ${cause?.javaClass?.simpleName ?: response?.status}")
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 10000
        }
        engine {
            config {
                val platformTrustManager = platformTrustManager()
                @SuppressLint("CustomX509TrustManager")
                val trustManager = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = platformTrustManager.acceptedIssuers

                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                        platformTrustManager.checkClientTrusted(chain, authType)

                    @SuppressLint("TrustAllX509TrustManager")
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        if (!ignoreSslErrors()) platformTrustManager.checkServerTrusted(chain, authType)
                    }
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustManager), null)
                sslSocketFactory(sslContext.socketFactory, trustManager)

                val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                hostnameVerifier { host, session -> ignoreSslErrors() || defaultVerifier.verify(host, session) }

                eventListenerFactory { ConnectionLogger() }
            }
        }
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/** Logs the connect phase of one call: what DNS returned and the outcome of each address tried. */
private class ConnectionLogger : EventListener() {
    private var callStart = 0L
    private var connectStart = 0L

    private fun elapsed() = System.currentTimeMillis() - callStart

    override fun callStart(call: Call) {
        callStart = System.currentTimeMillis()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        Timber.d("HTTP ${call.request().url}: DNS $domainName -> $inetAddressList (+${elapsed()}ms)")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = System.currentTimeMillis()
        Timber.d("HTTP ${call.request().url}: connecting to $inetSocketAddress via ${if (proxy == Proxy.NO_PROXY) "no proxy" else proxy} (+${elapsed()}ms)")
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        Timber.d("HTTP ${call.request().url}: connected to $inetSocketAddress in ${System.currentTimeMillis() - connectStart}ms")
    }

    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        Timber.w("HTTP ${call.request().url}: connect to $inetSocketAddress failed after ${System.currentTimeMillis() - connectStart}ms: ${ioe.javaClass.simpleName}: ${ioe.message}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        Timber.w("HTTP ${call.request().url}: call failed after ${elapsed()}ms: ${ioe.javaClass.simpleName}: ${ioe.message}")
    }
}
