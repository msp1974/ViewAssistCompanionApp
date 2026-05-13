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
import kotlinx.serialization.json.*
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import timber.log.Timber
import kotlin.random.Random

data class AuthToken(val tokenType: String = "", val accessToken: String = "", val expires: Long = 0, val refreshToken: String = "")

class AuthUtils(val config: APPConfig) {

    // Add external auth callback for HA authentication
    val externalAuthCallback = object : ExternalAuthCallback {
        private val refreshHandler = Handler(Looper.getMainLooper())
        private var refreshRunnable: Runnable? = null

        override fun onRequestExternalAuth(view: WebView, payload: String) {
            val json = Json { ignoreUnknownKeys = true }
            val payloadJson = json.parseToJsonElement(payload).jsonObject
            val force = payloadJson["force"]?.jsonPrimitive?.boolean ?: false
            log.d("External auth callback in progress...")
            setAuthStage(view, PageLoadingStage.AUTHORISING)
            val effectiveExpiry = getEffectiveTokenExpiry()
            val remainingMs = effectiveExpiry - System.currentTimeMillis()
            if (config.refreshToken == "") {
                Timber.d("No refresh token.  Proceeding to login screen")
                loadUrl(
                    view,
                    getAuthUrl(getHAUrl(config, withDashboardPath = false)),
                    clearCache = true
                )
                setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                return
            } else if (
                force ||
                remainingMs <= MIN_TOKEN_LIFETIME_FOR_PAGE_MS ||
                System.currentTimeMillis() > (effectiveExpiry - AUTH_REFRESH_BUFFER_MS)
            ) {
                when {
                    force -> Timber.d("HA forcing new token using refresh token")
                    remainingMs <= MIN_TOKEN_LIFETIME_FOR_PAGE_MS ->
                        log.d("Access token remaining lifetime ${remainingMs}ms is too short for page use. Refreshing first")
                    else -> log.d("Auth token is near expiry. Requesting new token using refresh token")
                }
                val success: Boolean = reAuthWithRefreshToken()
                if (success) {
                    Timber.d("Authorising with refreshed token")
                    callAuthJS(view)
                    setAuthStage(view, PageLoadingStage.AUTHORISED)
                } else {
                    log.d("Failed to refresh auth token. Proceeding to login screen")
                    setAuthStage(view, PageLoadingStage.AUTH_FAILED)
                    loadUrl(view, getAuthUrl(getHAUrl(config, withDashboardPath = false)), clearCache = true)
                }
            } else if (config.accessToken != "") {
                Timber.d("Auth token is still valid - authorising")
                setAuthStage(view, PageLoadingStage.AUTHORISED)
                callAuthJS(view)
            }
        }

        override fun onRequestRevokeExternalAuth(view: WebView) {
            Timber.d("External auth revoke callback in progress...")
            stopAuthHeartbeat()
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
            Timber.d("Loading URL: $url")
            Handler(Looper.getMainLooper()).post({
                if (clearCache) {
                    view.clearCache(true)
                }
                view.loadUrl(url)
            })
        }

        private fun callAuthJS(view: WebView) {
            Handler(Looper.getMainLooper()).post({
                val expiresInSeconds = ((getEffectiveTokenExpiry() - System.currentTimeMillis()) / 1000L)
                    .coerceAtLeast(60L)
                val js = """
                    (function() {
                        const token = {
                            access_token: "${config.accessToken}",
                            expires_in: $expiresInSeconds
                        };
                        const inject = function() {
                            if (typeof window.externalAuthSetToken !== "function") {
                                return false;
                            }
                            window.externalAuthSetToken(true, token);
                            return true;
                        };
                        if (inject()) {
                            return "called";
                        }
                        let attempts = 0;
                        const maxAttempts = 20;
                        const retryHandle = window.setInterval(function() {
                            attempts += 1;
                            if (inject() || attempts >= maxAttempts) {
                                window.clearInterval(retryHandle);
                            }
                        }, 500);
                        return "scheduled";
                    })();
                """.trimIndent()
                log.d("Auth JS injection expiresInSeconds=$expiresInSeconds")
                view.evaluateJavascript(js) { result ->
                    log.d("Auth JS callback result: $result")
                }
                ensureAuthHeartbeat(view)
            })
        }

        private fun ensureAuthHeartbeat(view: WebView) {
            if (refreshRunnable != null) {
                return
            }
            refreshRunnable = object : Runnable {
                override fun run() {
                    try {
                        val currentUrl = view.url.orEmpty()
                        val haBaseUrl = getHAUrl(config, withDashboardPath = false).removeSuffix("/")
                        if (config.refreshToken.isBlank() || config.accessToken.isBlank()) {
                            log.d("Auth heartbeat stopping because tokens are unavailable")
                            stopAuthHeartbeat()
                            return
                        }
                        if (!currentUrl.startsWith(haBaseUrl, ignoreCase = true)) {
                            log.d("Auth heartbeat skipped currentUrl=$currentUrl")
                        } else {
                            val now = System.currentTimeMillis()
                            val effectiveExpiry = getEffectiveTokenExpiry()
                            if (now > (effectiveExpiry - AUTH_REFRESH_BUFFER_MS)) {
                                log.d("Auth heartbeat proactively refreshing token")
                                if (reAuthWithRefreshToken()) {
                                    log.d("Auth heartbeat authorising with refreshed token")
                                    callAuthJS(view)
                                } else {
                                    log.d("Auth heartbeat failed to refresh token")
                                }
                            } else {
                                log.d("Auth heartbeat reinjecting current token effectiveExpiry=$effectiveExpiry")
                                callAuthJS(view)
                            }
                        }
                    } catch (e: Exception) {
                        log.e("Auth heartbeat error: ${e.message}")
                    } finally {
                        if (refreshRunnable != null) {
                            refreshHandler.postDelayed(this, AUTH_HEARTBEAT_INTERVAL_MS)
                        }
                    }
                }
            }
            log.d("Auth heartbeat started intervalMs=$AUTH_HEARTBEAT_INTERVAL_MS")
            refreshHandler.postDelayed(refreshRunnable!!, AUTH_HEARTBEAT_INTERVAL_MS)
        }

        private fun stopAuthHeartbeat() {
            refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
            refreshRunnable = null
        }

        private fun getEffectiveTokenExpiry(): Long {
            val jwtExpiry = getTokenExpiryFromJwt(config.accessToken)
            if (jwtExpiry != null) {
                if (config.tokenExpiry != jwtExpiry) {
                    config.tokenExpiry = jwtExpiry
                }
                return jwtExpiry
            }
            return config.tokenExpiry
        }

        private fun reAuthWithRefreshToken(): Boolean {
            Timber.d("Requesting new token using refresh token")
            val auth = refreshAccessToken(
                getHAUrl(config),
                config.refreshToken,
                !config.ignoreSSLErrors
            )
            if (auth.accessToken != "" && auth.expires > System.currentTimeMillis()) {
                log.d("Received new auth token")
                config.accessToken = auth.accessToken
                config.tokenExpiry = auth.expires
                return true
            } else {
                return false
            }
        }
    }

    companion object {
        val log = Logger()
        const val CLIENT_URL = "vaca.homeassistant"
        const val AUTH_HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        const val AUTH_REFRESH_BUFFER_MS = 10 * 60 * 1000L
        const val MIN_TOKEN_LIFETIME_FOR_PAGE_MS = 25 * 60 * 1000L
        var state: String = ""

        fun getHAUrl(config: APPConfig, withDashboardPath: Boolean = true): String {
            val url = if (config.homeAssistantURL == "") {
                "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
            } else {
                config.homeAssistantURL.removeSuffix("/")
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
            state = buildString(32) {
                repeat(32) { append(charset[Random.Default.nextInt(charset.length)]) }
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
            log.d("URL: $url Auth code: $authCode, client id: ${getClientId()}")
            val response = httpPOST(url, map, verifySSL)
            try {
                val json = Json.parseToJsonElement(response).jsonObject
                val accessToken = json["access_token"]?.jsonPrimitive?.content ?: ""
                val tokenType = json["token_type"]?.jsonPrimitive?.content ?: ""
                val refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: ""
                
                if (accessToken.isEmpty() || tokenType.isEmpty()) {
                    log.e("Authentication failed: Required token fields missing")
                    return AuthToken()
                }

                val expiresInSeconds = json["expires_in"]?.jsonPrimitive?.intOrNull
                if (expiresInSeconds == null || expiresInSeconds <= 0) {
                    log.e("Authentication failed: invalid expires_in")
                    return AuthToken()
                }
                
                val expiresIn = getTokenExpiryFromJwt(accessToken)
                    ?: (System.currentTimeMillis() + (expiresInSeconds * 1000L))

                return AuthToken(
                    tokenType,
                    accessToken,
                    expiresIn,
                    refreshToken
                )
            } catch (e: Exception) {
                log.e("Failed to parse auth response: ${e.message}")
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
            Timber.d("URL: $url Refresh token: ${refreshToken.substring(20)}..., client id: ${getClientId()}")
            val response = httpPOST(url, map, verifySSL)
            try {
                val json = Json.parseToJsonElement(response).jsonObject
                
                val accessToken = json["access_token"]?.jsonPrimitive?.content ?: ""
                val tokenType = json["token_type"]?.jsonPrimitive?.content ?: ""
                val expiresInSeconds = json["expires_in"]?.jsonPrimitive?.intOrNull
                
                if (accessToken.isEmpty() || tokenType.isEmpty()) {
                    Timber.e("Token refresh failed: access_token or token_type fields missing")
                    return AuthToken()
                }

                if (expiresInSeconds == null || expiresInSeconds <= 0) {
                    Timber.e("Token refresh failed: invalid expires_in")
                    return AuthToken()
                }

                val expiresIn = getTokenExpiryFromJwt(accessToken)
                    ?: (System.currentTimeMillis() + (expiresInSeconds * 1000L))

                return AuthToken(
                    tokenType,
                    accessToken,
                    expiresIn,
                )
            } catch (e: Exception) {
                Timber.e("Failed to parse refresh response: ${e.message}")
                return AuthToken()
            }

        }

        fun httpPOST(url: String, parameters: HashMap<String, String>, verifySSL: Boolean = true): String {
            val client = if (verifySSL) httpClient else httpClientTrustAll
            val builder = FormBody.Builder()
            val it = parameters.entries.iterator()

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

        fun getTokenExpiryFromJwt(accessToken: String): Long? {
            if (accessToken.isBlank()) {
                return null
            }
            return try {
                val parts = accessToken.split(".")
                if (parts.size < 2) {
                    return null
                }
                val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                val json = Json.parseToJsonElement(payload).jsonObject
                json["exp"]?.jsonPrimitive?.longOrNull?.times(1000L)
            } catch (e: Exception) {
                log.w("Unable to parse JWT expiry: ${e.message}")
                null
            }
        }

        val httpClient: OkHttpClient = OkHttpClient()
        val httpClientTrustAll: OkHttpClient by lazy {
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
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
