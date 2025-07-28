package com.msp1974.vacompanion.utils

import android.net.Uri
import kotlin.random.Random
import androidx.core.net.toUri
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue

data class AuthToken(val tokenType: String = "", val accessToken: String = "", val expires: Long = 0, val refreshToken: String = "")

class AuthUtils {

    companion object {
        val log = Logger()
        const val CLIENT_URL = "vaca.homeassistant"
        var state: String = ""

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

        fun authoriseWithAuthCode(baseUrl: String, authCode: String): AuthToken {
            val url: String = getTokenUrl(baseUrl)
            val map: HashMap<String, String> = hashMapOf(
                "grant_type" to "authorization_code",
                "client_id" to getClientId(),
                "code" to authCode
            )

            val response = httpPOST(url, map)
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

        fun refreshAccessToken(host: String, refreshToken: String): AuthToken {
            val url: String = getTokenUrl(host)
            val map: HashMap<String, String> = hashMapOf(
                "grant_type" to "refresh_token",
                "client_id" to getClientId(),
                "refresh_token" to refreshToken
            )
            val response = httpPOST(url, map)
            try {
                val json = JSONObject(response)
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

        fun httpPOST(url: String, parameters: HashMap<String, String>): String {
            var client = OkHttpClient()
            val queue = LinkedBlockingQueue<String>()
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
            Thread {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.e("Unexpected code $response")
                        queue.add("")
                    }
                    try {
                        queue.add(response.body()?.string().toString())
                    } catch (e: Exception) {
                        log.e(e.message.toString())
                        queue.add("")
                    }
                }
            }.start()

            return queue.take()
        }
    }
}