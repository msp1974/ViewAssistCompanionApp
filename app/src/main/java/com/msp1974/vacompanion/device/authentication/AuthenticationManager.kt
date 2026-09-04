package com.msp1974.vacompanion.device.authentication

import androidx.core.net.toUri
import com.msp1974.vacompanion.settings.APPConfig
import io.ktor.client.call.body
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URL

class AuthenticationManager(
    val config: APPConfig
) {
    private val authenticationService = AuthenticationService()
    private val sessionMutex = Mutex()

    suspend fun buildBearerToken(): String {
        ensureValidSession()
        return "Bearer " + config.accessToken
    }

    suspend fun ensureValidSession(forceRefresh: Boolean = false) {
        val accessTokenBeforeLock = config.accessToken

        sessionMutex.withLock {
            // Another external-auth request may have refreshed the session while this
            // request was waiting for the lock. In that case, reuse the new token even
            // if both requests arrived with force=true.
            val refreshedWhileWaiting =
                accessTokenBeforeLock != config.accessToken && !isExpired()
            val refreshRequired =
                isExpired() || config.accessToken.isBlank() || (forceRefresh && !refreshedWhileWaiting)

            if (refreshRequired) {
                if (config.refreshToken.isBlank()) {
                    throw AuthenticationException("No refresh token available", 0, null)
                }
                // Do not swallow refresh failures. Callers must never treat an expired
                // access token as a successful external-auth response.
                refreshSessionWithToken(config.refreshToken)
            }
        }
    }

    suspend fun getAccessToken(code: String) {
        return authenticationService.getToken(
            url = getBaseUrl(),
            code = code,
        ).let {
            config.accessToken = it.accessToken
            config.refreshToken = it.refreshToken.toString()
            config.tokenExpiry = System.currentTimeMillis() + (it.expiresIn * 1000)
            return@let
        }
    }

    private suspend fun refreshSessionWithToken(refreshToken: String) {
        return authenticationService.refreshToken(
            url = getBaseUrl(),
            refreshToken = refreshToken,
        ).let { response ->
            if (response.status.isSuccess()) {
                val refreshedToken = response.body<Token>()
                //TODO: Make this an object on DeviceManager session info
                config.accessToken = refreshedToken.accessToken
                config.tokenExpiry = System.currentTimeMillis() + (refreshedToken.expiresIn * 1000)
                return@let
            }

            val errorBody = response.body<String?>()
            if (response.status.value == 400 && errorBody?.contains("invalid_grant") == true) {
                // The refresh credential is no longer usable. Clear the local session
                // without making another request with the already-invalid token.
                config.accessToken = ""
                config.refreshToken = ""
                config.tokenExpiry = 0
            }
            throw AuthenticationException("Failed to refresh token", response.status.value, errorBody)
        }
    }

    suspend fun revokeSession() {
        authenticationService.revokeToken(getBaseUrl(), config.refreshToken)
        config.refreshToken = ""
    }

    fun getBaseUrl(): URL {
        return if (config.homeAssistantURL == "") {
            URL("http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}")
        } else {
            URL(config.homeAssistantURL.removeSuffix("/"))
        }
    }

    fun getHAUrl(): String {
        val url = getBaseUrl().toString().toUri()
            .buildUpon()
            .path(config.homeAssistantDashboard)
            .appendQueryParameter("external_auth", "1")
            .build()
        return url.toString()
    }

    fun getExternalAuthUrl(): String {
        val base = getBaseUrl()
        val isSSL = base.protocol == IAuthenticationService.HTTPS
        val url = base.toString().toUri()
            .buildUpon()
            .path("")
            .appendPath("auth")
            .appendPath("authorize")
            .appendQueryParameter("client_id", authenticationService.getClientId(isSSL))
            .appendQueryParameter("redirect_uri", authenticationService.getClientId(isSSL))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "homeassistant")
            .build()
        return url.toString()
    }

    fun isExpired() = (expiresIn() < 0)
    fun expiresIn() = config.tokenExpiry.let { config.tokenExpiry - System.currentTimeMillis() }
}
