package com.msp1974.vacompanion.device.authentication

import android.annotation.SuppressLint
import com.msp1974.vacompanion.device.authentication.IAuthenticationService.Companion.SEGMENT_AUTH_REVOKE
import com.msp1974.vacompanion.device.authentication.IAuthenticationService.Companion.SEGMENT_AUTH_TOKEN
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import java.net.URL

interface IAuthenticationService {

    companion object {
        const val CLIENT_ID = "http://vaca.homeassistant"
        const val GRANT_TYPE_CODE = "authorization_code"
        const val GRANT_TYPE_REFRESH = "refresh_token"

        const val SEGMENT_AUTH_TOKEN = "auth/token"
        const val SEGMENT_AUTH_REVOKE = "auth/revoke"
    }

    suspend fun getToken(
        url: URL,
        grantType: String = GRANT_TYPE_CODE,
        code: String,
        clientId: String = CLIENT_ID,
    ): Token

    suspend fun refreshToken(
        url: URL,
        grantType: String = GRANT_TYPE_REFRESH,
        refreshToken: String,
        clientId: String = CLIENT_ID,
    ): HttpResponse

    suspend fun revokeToken(
        url: URL,
        token: String,
    )
}


class AuthenticationService : IAuthenticationService {

    val client = HttpClientProvider().get()

    override suspend fun getToken(
        url: URL,
        grantType: String,
        code: String ,
        clientId: String
    ): Token {
        return client.post(url.toString().removeSuffix("/") + "/" + SEGMENT_AUTH_TOKEN) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters {
                append("grant_type", grantType)
                append("code", code)
                append("client_id", clientId)
            }))
        }.body<Token>()
    }

    override suspend fun refreshToken(
        url: URL,
        grantType: String,
        refreshToken: String,
        clientId: String
    ): HttpResponse {
        return client.post(url.toString().removeSuffix("/") + "/" + SEGMENT_AUTH_TOKEN) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(parameters {
                append("grant_type", grantType)
                append("refresh_token", refreshToken)
                append("client_id", clientId)
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