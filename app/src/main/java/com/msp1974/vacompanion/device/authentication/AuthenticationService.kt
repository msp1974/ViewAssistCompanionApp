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
    ): Token

    suspend fun refreshToken(
        url: URL,
        grantType: String = GRANT_TYPE_REFRESH,
        refreshToken: String,
        clientId: String? = null,
    ): HttpResponse

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
            val auth_url = url.toString().removeSuffix("/") + "/" + SEGMENT_AUTH_TOKEN
            val cid = clientId ?: getClientId(url.protocol == IAuthenticationService.HTTPS)
            Timber.d("Requesting token from HA: $auth_url -> $cid -> $code")
            val response = client.post(auth_url) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(parameters {
                    append("grant_type", grantType)
                    append("code", code)
                    append("client_id", cid)
                }))
            }
            Timber.d("Token response: ${response.body<String>()}")
            return response.body<Token>()
        } catch (e: Exception) {
            throw AuthenticationException("Failed to get token", 0, null)
        }
    }

    override suspend fun refreshToken(
        url: URL,
        grantType: String,
        refreshToken: String,
        clientId: String?
    ): HttpResponse {
        return client.post(url.toString().removeSuffix("/") + "/" + SEGMENT_AUTH_TOKEN) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters {
                append("grant_type", grantType)
                append("refresh_token", refreshToken)
                append("client_id", clientId?:getClientId(url.protocol == IAuthenticationService.HTTPS))
            }))
        }
    }

    override suspend fun revokeToken(url: URL, token: String) {
        client.post(url.toString().removeSuffix("/") + "/" + SEGMENT_AUTH_REVOKE) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters {
                append("token", token)
            }))
        }
    }
}