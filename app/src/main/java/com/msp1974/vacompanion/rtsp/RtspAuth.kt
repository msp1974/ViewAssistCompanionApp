package com.msp1974.vacompanion.rtsp

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Optional HTTP Digest (RFC 2617) / Basic auth for the RTSP server, used only when both a
 * username and password are configured (see [com.msp1974.vacompanion.settings.APPConfig.rtspUsername]).
 * Comparisons are constant-time via [MessageDigest.isEqual].
 */
class RtspAuth(private val username: String, private val password: String) {

    fun challengeHeaders(nonce: String): List<String> {
        return listOf(
            "Digest realm=\"$REALM\", nonce=\"$nonce\", algorithm=MD5",
            "Basic realm=\"$REALM\""
        )
    }

    fun validate(authorizationHeader: String?, method: String, nonce: String): Boolean {
        if (authorizationHeader.isNullOrBlank()) return false
        return when {
            authorizationHeader.startsWith("Digest ", ignoreCase = true) ->
                validateDigest(authorizationHeader.removePrefix("Digest ").trim(), method, nonce)
            authorizationHeader.startsWith("Basic ", ignoreCase = true) ->
                validateBasic(authorizationHeader.removePrefix("Basic ").trim())
            else -> false
        }
    }

    private fun validateBasic(encoded: String): Boolean {
        return try {
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            val parts = decoded.split(":", limit = 2)
            if (parts.size != 2) return false
            constantTimeEquals(parts[0], username) && constantTimeEquals(parts[1], password)
        } catch (e: Exception) {
            false
        }
    }

    private fun validateDigest(params: String, method: String, expectedNonce: String): Boolean {
        val values = parseParams(params)
        val reqUsername = values["username"] ?: return false
        val reqNonce = values["nonce"] ?: return false
        val reqUri = values["uri"] ?: return false
        val reqResponse = values["response"] ?: return false

        if (reqNonce != expectedNonce) return false
        if (!constantTimeEquals(reqUsername, username)) return false

        val ha1 = md5("$username:$REALM:$password")
        val ha2 = md5("$method:$reqUri")
        val expected = md5("$ha1:$reqNonce:$ha2")

        return constantTimeEquals(expected, reqResponse)
    }

    private fun parseParams(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // key="value" or key=value, comma-separated.
        val regex = Regex("""(\w+)=("([^"]*)"|[^,]*)""")
        regex.findAll(input).forEach { match ->
            val key = match.groupValues[1]
            val quoted = match.groupValues[3]
            val value = if (quoted.isNotEmpty()) quoted else match.groupValues[2].trim().trim('"')
            result[key] = value
        }
        return result
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val REALM = "VACA"
        private val random = SecureRandom()

        fun generateNonce(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
