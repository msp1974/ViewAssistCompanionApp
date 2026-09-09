package com.msp1974.vacompanion.device.authentication

import com.msp1974.vacompanion.device.authentication.IAuthenticationService.Companion.SEGMENT_AUTH_REVOKE
import com.msp1974.vacompanion.device.authentication.IAuthenticationService.Companion.SEGMENT_AUTH_TOKEN
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import timber.log.Timber
import java.net.URL

interface IAuthenticationService {

    companion object {
        const val HTTP = "http"
        const val HTTPS = "https"
        const val CLIENT_ID = "vaca.homeassistant"
        const val GRANT_TYPE_CODE = "authorization_code"
        const val GRANT_TYPE_REFRESH = "refresh_token"

        const val SEGMENT_AUTH_TOKEN = "auth/token"
        const val SEGMENT_AUTH_REVOKE = "auth/revoke"
    }

    fun getClientId(isSSL: Boolean): String {
        if (isSSL) {
            return "${HTTPS}://${CLIENT_ID}"
        }
        return "${HTTP}://${CLIENT_ID}"
    }

    suspend fun getToken(
        url: URL,
        grantType: String = GRANT_TYPE_CODE,
        code: String,
        clientId: String? = null,
    ): Token?

    suspend fun refreshToken(
        url: URL,
        grantType: String = GRANT_TYPE_REFRESH,
        refreshToken: String,
        clientId: String? = null,
    ): Token?

    suspend fun revokeToken(
        url: URL,
        token: String,
    )
}


class AuthenticationService() : IAuthenticationService {

    val client = HttpClientProvider().get()

    override suspend fun getToken(
        url: URL,
        grantType: String,
        code: String ,
        clientId: String?
    ): Token {
        try {
            val authUrl = url.toString().removeSuffix("/") + "/" + SEGMENT_AUTH_TOKEN
            val cid = clientId ?: getClientId(url.protocol == IAuthenticationService.HTTPS)
            val obfuscatedSource = "{'grant_type': '$grantType', 'code': '${code.subSequence(0,10)}...', 'client_id': '$cid'}"
            Timber.d("getToken Request: $authUrl -> $obfuscatedSource")
            val response = client.post(authUrl) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(parameters {
                    append("grant_type", grantType)
                    append("code", code)
                    append("client_id", cid)
                }))
            }
            val result = response.body<Token>()
            val obfuscatedResult = "{'access_token': '${result.accessToken.subSequence(0,10)}...', 'expires_in': ${result.expiresIn}, 'refresh_token': '${result.refreshToken?.subSequence(0,10)}...', 'token_type': '${result.tokenType}'}"
            Timber.d("getToken Result: $obfuscatedResult")
            return result
        } catch (e: Exception) {
            throw AuthenticationException("Failed to get token", 0, e.message)
        }
    }

    override suspend fun refreshToken(
        url: URL,
        grantType: String,
        refreshToken: String,
        clientId: String?
    ): Token {
        try {
            val authUrl = url.toString().removeSuffix("/") + "/" + SEGMENT_AUTH_TOKEN
            val cid = clientId ?: getClientId(url.protocol == IAuthenticationService.HTTPS)
            val obfuscatedSource = "{'grant_type': '$grantType', 'refresh_token': '${refreshToken.subSequence(0,10)}...', 'client_id': '${cid}'}"
            Timber.d("refreshToken Request: $authUrl -> $obfuscatedSource")
            val response = client.post(authUrl) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(parameters {
                    append("grant_type", grantType)
                    append("refresh_token", refreshToken)
                    append("client_id", cid)
                }))
            }
            if (response.status.isSuccess()) {
                val result = response.body<Token>()
                val obfuscatedResult = "{'access_token': '${result.accessToken.subSequence(0,10)}...', 'expires_in': ${result.expiresIn}, 'refresh_token': '${result.refreshToken?.subSequence(0,10)}...', 'token_type': '${result.tokenType}'}"
                Timber.d("refreshToken Result: $obfuscatedResult")
                return result
            }
            throw AuthenticationException("Failed to refresh token", response.status.value, response.body<String>())
        } catch (e: Exception) {
            // Network/TLS failures (e.g. a misconfigured HA URL whose certificate doesn't cover
            // the configured host) must surface as AuthenticationException like getToken() does,
            // not escape as a raw exception - callers only catch AuthenticationException.
            throw AuthenticationException("Failed to refresh token", 0, e.message)
        }
    }

    override suspend fun revokeToken(url: URL, token: String) {
        try {
            client.post(url.toString().removeSuffix("/") + "/" + SEGMENT_AUTH_REVOKE) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(parameters {
                    append("token", token)
                }))
            }
        } catch (e: Exception) {
            throw AuthenticationException("Failed to revoke token", 0, e.message)
        }
    }
}