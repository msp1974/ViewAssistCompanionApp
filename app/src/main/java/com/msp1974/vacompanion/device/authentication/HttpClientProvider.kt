package com.msp1974.vacompanion.device.authentication

import android.annotation.SuppressLint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class HttpClientProvider {
    fun get(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(HttpRequestRetry) { // Should be installed before HttpTimeout
            maxRetries = 5
            retryIf { request, response ->
                !response.status.isSuccess() && (response.status.value != 400)
            }
            retryOnException(retryOnTimeout = true)
            exponentialDelay()
        }
        install(HttpTimeout)
        engine {
            https {
                trustManager = TrustAllX509TrustManager()
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