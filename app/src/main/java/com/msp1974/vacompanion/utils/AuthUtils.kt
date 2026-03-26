package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.net.toUri
import com.msp1974.vacompanion.jsinterface.ExternalAuthCallback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import kotlin.random.Random

data class AuthToken(val tokenType: String = "", val accessToken: String = "", val expires: Long = 0, val refreshToken: String = "")

class AuthUtils(val config: APPConfig) {

    // Add external auth callback for HA authentication
    val externalAuthCallback = object : ExternalAuthCallback {
        override fun onRequestExternalAuth(view: WebView) {
            log.d("External auth callback in progress...")
            setAuthStage(view, PageLoadingStage.AUTHORISING)
            if (config.refreshToken == "") {
                log.d("No refresh token.  Proceeding to login screen")
                loadUrl(view, getAuthUrl(getHAUrl(config, withDashboardPath = false)), clearCache = true)
                setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                return
            } else if (System.currentTimeMillis() > (config.tokenExpiry - 120) && config.refreshToken != "") {
                // Token will expire in less than 2 mins, consider expired
                // Need to get new access token as it has expired
                log.d("Auth token has expired.  Requesting new token using refresh token")
                val success: Boolean = reAuthWithRefreshToken()
                if (success) {
                    log.d("Authorising with new token")
                    callAuthJS(view)
                    setAuthStage(view, PageLoadingStage.AUTHORISED)
                } else {
                    log.d("Failed to refresh auth token.  Proceeding to login screen")
                    setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                    loadUrl(view, getAuthUrl(getHAUrl(config, withDashboardPath = false)), clearCache = true)
                }
            } else if (config.accessToken != "") {
                log.d("Auth token is still valid - authorising")
                setAuthStage(view, PageLoadingStage.AUTHORISED)
                callAuthJS(view)
            }
        }

        override fun onRequestRevokeExternalAuth(view: WebView) {
            log.d("External auth revoke callback in progress...")
            config.accessToken = ""
            config.refreshToken = ""
            config.tokenExpiry = 0
            setAuthStage(view, PageLoadingStage.AUTH_FAILED)
            loadUrl(view, getAuthUrl(getHAUrl(config)))
        }

        private fun setAuthStage(view: WebView, stage: PageLoadingStage) {
            Handler(Looper.getMainLooper()).post({
                val w = view as CustomWebView
                w.setPageLoadingState(stage)
            })
        }

        private fun loadUrl(view: WebView, url: String, clearCache: Boolean = false) {
            log.d("Loading URL: $url")
            Handler(Looper.getMainLooper()).post({
                if (clearCache) {
                    view.clearCache(true)
                }
                view.loadUrl(url)
            })
        }

        private fun callAuthJS(view: WebView) {
            Handler(Looper.getMainLooper()).post({
                view.evaluateJavascript(
                    "window.externalAuthSetToken(true, {\n" +
                            "\"access_token\": \"${config.accessToken}\",\n" +
                            "\"expires_in\": 1800\n" +
                            "});",
                    null
                )
            })
        }

        private fun reAuthWithRefreshToken(): Boolean {
            log.d("Auth token has expired.  Requesting new token using refresh token")
            val auth = refreshAccessToken(
                getHAUrl(config),
                config.refreshToken,
                !config.ignoreSSLErrors
            )
            if (auth.accessToken != "" && auth.expires > System.currentTimeMillis()) {
                log.d("Received new auth token")
                config.accessToken = auth.accessToken

                // Manage any diff to device time
                val diff = auth.expires - System.currentTimeMillis()
                config.tokenExpiry = System.currentTimeMillis() + diff
                return true
            } else {
                return false
            }
        }

        private fun tokenExpiryRefreshTask(view: WebView) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (System.currentTimeMillis() > config.tokenExpiry && config.refreshToken != "") {
                        if (reAuthWithRefreshToken()) {
                            callAuthJS(view)
                        }
                    }
                    tokenExpiryRefreshTask(view)
                } catch (e: Exception) {}
            }, 30000)
        }
    }

    companion object {
        val log = Logger()
        const val CLIENT_URL = "vaca.homeassistant"
        var state: String = ""

        fun getHAUrl(config: APPConfig, withDashboardPath: Boolean = true): String {
            var url = ""
            if (config.homeAssistantURL == "") {
                url = "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
            } else {
                url = config.homeAssistantURL.removeSuffix("/")
            }

            if (withDashboardPath && config.homeAssistantDashboard != "") {
                return url + "/" + config.homeAssistantDashboard.removePrefix("/")
            }
            return url
        }

        fun getURL(baseUrl: String): String {
            log.d("Getting URL for $baseUrl")
            val url = baseUrl.toUri()
                .buildUpon()
                .appendQueryParameter("external_auth", "1")
                .build()
            return url.toString()
        }

        fun getAuthUrl(baseUrl: String): String {
            log.d("Getting Auth URL for $baseUrl")
            val url = baseUrl.toUri()
                .buildUpon()
                .path("")
                .appendPath("auth")
                .appendPath("authorize")
                .appendQueryParameter("client_id", getClientId())
                .appendQueryParameter("redirect_uri", getRedirectUri())
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("state", generateState())
                .appendQueryParameter("scope", "homeassistant")
                .build()
            return url.toString()
        }

        fun getTokenUrl(baseUrl: String): String {
            val url = baseUrl.toUri()
                .buildUpon()
                .path("")
                .appendPath("auth")
                .appendPath("token")
            return url.build().toString()
        }

        private fun generateState(): String {
            val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = Random.Default
            state = ""
            repeat(32) {
                state += charset[random.nextInt(0, charset.length)]
            }
            return state
        }

        private fun getClientId(): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.authority(CLIENT_URL)
            return builder.build().toString()
        }

        private fun getRedirectUri(): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.authority(CLIENT_URL)
            builder.appendQueryParameter("auth_callback","1")
            return builder.build().toString()

        }

        fun validateAuthResponse(url: String): Boolean {
            val uri = url.toUri()
            return uri.authority == CLIENT_URL && uri.getQueryParameter("state") == state
        }

        fun getReturnAuthCode(url: String): String {
            if (validateAuthResponse(url)) {
                return url.toUri().getQueryParameter("code")!!
            } else {
                return ""
            }
        }

        fun authoriseWithAuthCode(baseUrl: String, authCode: String, verifySSL: Boolean = true): AuthToken {
            val url: String = getTokenUrl(baseUrl)
            val map: HashMap<String, String> = hashMapOf(
                "grant_type" to "authorization_code",
                "client_id" to getClientId(),
                "code" to authCode
            )
            log.d("URL: ${getTokenUrl(baseUrl)} Auth code: $authCode, client id: ${getClientId()}")
            val response = httpPOST(url, map, verifySSL)
            try {
                val json = JSONObject(response)
                val expiresIn = System.currentTimeMillis() + (json.getString("expires_in").toInt() * 1000)


                return AuthToken(
                    json.getString("token_type"),
                    json.getString("access_token"),
                    expiresIn,
                    json.getString("refresh_token")
                )
            } catch (e: Exception) {
                log.e(e.message.toString())
                return AuthToken()
            }
        }

        fun refreshAccessToken(host: String, refreshToken: String, verifySSL: Boolean = true): AuthToken {
            val url: String = getTokenUrl(host)
            val map: HashMap<String, String> = hashMapOf(
                "grant_type" to "refresh_token",
                "client_id" to getClientId(),
                "refresh_token" to refreshToken
            )
            log.d("URL: ${getTokenUrl(host)} Refresh token: $refreshToken, client id: ${getClientId()}")
            val response = httpPOST(url, map, verifySSL)
            try {
                val json = JSONObject(response)
                log.d("JSON reposne: $json")
                val expiresIn = System.currentTimeMillis() + (json.getString("expires_in").toInt() * 1000)

                return AuthToken(
                    json.getString("token_type"),
                    json.getString("access_token"),
                    expiresIn,
                )
            } catch (e: Exception) {
                log.e(e.message.toString())
                return AuthToken()
            }

        }

        fun httpPOST(url: String, parameters: HashMap<String, String>, verifySSL: Boolean = true): String {
            val clientBuilder = OkHttpClient.Builder()
            val builder = FormBody.Builder()
            val it = parameters.entries.iterator()

            if (!verifySSL) {
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                clientBuilder.sslSocketFactory(
                    sslContext.socketFactory,
                    trustAllCerts[0] as X509TrustManager
                )
                clientBuilder.hostnameVerifier { hostname, session -> true }
            }
            val client = clientBuilder.build()


            while (it.hasNext()) {
                val pair = it.next() as Map.Entry<*, *>
                builder.add(pair.key.toString(), pair.value.toString())
            }

            val formBody = builder.build()
            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.e("Unexpected code $response")
                        return ""
                    }
                    return response.body.string()
                }
            } catch (e: Exception) {
                log.e("Error authorising with HA: ${e.message.toString()}")
                return ""
            }
        }

        val trustAllCerts = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(
                chain: Array<out java.security.cert.X509Certificate?>?,
                authType: String?
            ) {}

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(
                chain: Array<out java.security.cert.X509Certificate?>?,
                authType: String?
            ) {}

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate?> {
                return arrayOf<java.security.cert.X509Certificate?>()
            }
        })
    }
}