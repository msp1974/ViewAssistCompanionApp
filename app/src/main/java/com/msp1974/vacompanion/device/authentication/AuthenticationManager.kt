package com.msp1974.vacompanion.device.authentication

import androidx.core.net.toUri
import com.msp1974.vacompanion.settings.APPConfig
import io.ktor.client.call.body
import io.ktor.http.isSuccess
import timber.log.Timber
import java.net.ConnectException
import java.net.URL

class AuthenticationManager(
    val config: APPConfig
) {
    private val authenticationService = AuthenticationService()

    suspend fun buildBearerToken(): String {
        ensureValidSession()
        return "Bearer " + config.accessToken
    }

    suspend fun ensureValidSession(forceRefresh: Boolean = false) {
        if (isExpired() || forceRefresh || config.accessToken == "") {
            try {
                refreshSessionWithToken(config.refreshToken)
            } catch (e: Exception) {
                Timber.e("Failed to refresh token: ${e.message}")
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
        ).let {
            if (it.status.isSuccess()) {
                val refreshedToken = it.body<Token>()
                //TODO: Make this an object on DeviceManager session info
                config.accessToken = refreshedToken.accessToken
                config.tokenExpiry = System.currentTimeMillis() + (refreshedToken.expiresIn * 1000)
                return@let
            } else if (it.status.value == 400 && it.body<String>().contains("invalid_grant")
            ) {
                revokeSession()
            }
            throw AuthenticationException("Failed to refresh token", it.status.value, it.body<String?>())
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
        val url = getBaseUrl().toString().toUri()
            .buildUpon()
            .path("")
            .appendPath("auth")
            .appendPath("authorize")
            .appendQueryParameter("client_id", IAuthenticationService.CLIENT_ID)
            .appendQueryParameter("redirect_uri", IAuthenticationService.CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "homeassistant")
            .build()
        return url.toString()
    }

    fun isExpired() = (expiresIn() < 0)
    fun expiresIn() = config.tokenExpiry.let { config.tokenExpiry - System.currentTimeMillis() }
}