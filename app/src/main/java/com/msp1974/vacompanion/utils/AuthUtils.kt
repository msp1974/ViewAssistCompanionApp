package com.msp1974.vacompanion.utils

import android.net.Uri
import kotlin.random.Random
import androidx.core.net.toUri
import com.msp1974.vacompanion.settings.APPConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.time.DurationUnit
import kotlin.time.toDuration

data class AuthToken(val tokenType: String = "", val accessToken: String = "", val expires: Long = 0, val refreshToken: String = "")

class AuthUtils {

    companion object {
        val log = Logger()
        const val CLIENT_URL = "vaca.homeassistant"
        var state: String = ""

        fun getURL(host: String): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.encodedAuthority(host)
            builder.appendQueryParameter("external_auth", "1")
            return builder.build().toString()
        }

        fun getAuthUrl(host: String): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.encodedAuthority(host)
            builder.appendPath("auth")
            builder.appendPath("authorize")

            builder.appendQueryParameter("client_id", getClientId())
            builder.appendQueryParameter("redirect_uri", getRedirectUri())
            builder.appendQueryParameter("response_type", "code")
            builder.appendQueryParameter("state", generateState())
            return builder.build().toString()
        }

        fun getTokenUrl(host: String): String {
            val builder = Uri.Builder()
            builder.scheme("http")
            builder.encodedAuthority(host)
            builder.appendPath("auth")
            builder.appendPath("token")
            return builder.build().toString()
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

        fun authoriseWithAuthCode(host: String, authCode: String): AuthToken {
            val url: String = getTokenUrl(host)
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

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.e("Unexpected code $response")
                    return ""
                }
                try {
                    return response.body()?.string().toString()
                } catch (e: Exception) {
                    log.e(e.message.toString())
                    return ""
                }
            }
        }

        fun generateInjectionJS(config: APPConfig): String {
            val authData = JSONObject()
            authData.put("access_token", config.accessToken)
            authData.put("refresh_token", config.refreshToken)
            authData.put("expires", config.tokenExpiry)
            authData.put("expires_in", 1800)
            authData.put("token_type", "Bearer")
            authData.put("client_id", getClientId())
            authData.put("hassUrl", getURL(config.homeAssistantHTTPServerHost))

            val jsonData = authData.toString()

            return """
            try {
                localStorage.setItem('hassTokens', '$jsonData');
                console.log('Injected hassTokens via VACA helper');
            } catch (e) {
                console.error('Error injecting hassTokens:', e);
                console.log('DATA: $jsonData');
            }
            """


        }
    }
}