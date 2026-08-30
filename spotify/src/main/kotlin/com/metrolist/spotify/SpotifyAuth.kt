package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyInternalToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

/**
 * Handles Spotify authentication using the web player's internal token endpoint.
 * Uses sp_dc cookies (extracted from WebView login) to obtain access tokens
 * without requiring a Spotify Developer Client ID.
 *
 * Token acquisition requires a TOTP (Time-based One-Time Password) generated
 * from a shared secret that Spotify rotates periodically. The secret and its
 * version are fetched from a community-maintained GitHub Gist.
 *
 * Reference: https://github.com/sonic-liberation/spotube-plugin-spotify
 */
object SpotifyAuth {
    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val RAW_NUANCE_GIST_URL =
        "https://gist.githubusercontent.com/sonic-liberation/22ed9c6ba463899e933427f7de1f0eef/raw"
    private const val NUANCE_GIST_API_URL =
        "https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    const val LOGIN_URL =
        "https://accounts.spotify.com/login"
    const val DIRECT_LOGIN_URL =
        "https://accounts.spotify.com/en/login"
    const val WEB_PLAYER_URL =
        "https://open.spotify.com"

    // Direct entry to Spotify's web signup flow.
    const val SIGNUP_URL = "https://www.spotify.com/signup"

    /**
     * Generates a JavaScript snippet to inject username and password into Spotify's web login form
     * and trigger the submission action safely.
     */
    fun getCredentialInjectionScript(username: String, password: String): String {
        val safeUser = username.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
        val safePass = password.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
        return """
            (function() {
                try {
                    var u = document.querySelector('input#login-username, input[data-testid="login-username"], input[type="text"], input[type="email"]');
                    var p = document.querySelector('input#login-password, input[data-testid="login-password"], input[type="password"]');
                    if (u) {
                        u.value = '$safeUser';
                        u.dispatchEvent(new Event('input', { bubbles: true }));
                        u.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    if (p) {
                        p.value = '$safePass';
                        p.dispatchEvent(new Event('input', { bubbles: true }));
                        p.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    setTimeout(function() {
                        var btn = document.querySelector('button#login-button, button[data-testid="login-button"], button[type="submit"]');
                        if (btn) {
                            btn.click();
                        }
                    }, 250);
                } catch(e) {
                    console.error("Sepotify credential injection error:", e);
                }
            })();
        """.trimIndent()
    }

    private val FALLBACK_NUANCE = Nuance(
        s = "GM3TMMJTGYZTQNZVGM4DINJZHA4TGOBYGMZTCMRTGEYDSMJRHE4TEOBUG4YTCMRUGQ4DQOJUGQYTAMRRGA2TCMJSHE3TCMBY",
        v = 61,
    )

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class Nuance(val s: String, val v: Int)

    @Serializable
    private data class GistFile(val content: String)

    @Serializable
    private data class GistFiles(val files: Map<String, GistFile>)

    @Serializable
    private data class ServerTimeResponse(val serverTime: Long)

    /**
     * Fetches an internal web-player access token using session cookies and TOTP.
     *
     * 1. Fetches the TOTP secret from the community Gist
     * 2. Gets the server time from Spotify
     * 3. Generates a 6-digit TOTP (SHA1, 30s interval)
     * 4. Calls /api/token with the TOTP and sp_dc cookie
     */
    suspend fun fetchAccessToken(
        spDc: String,
        spKey: String = "",
    ): Result<SpotifyInternalToken> = runCatching {
        val nuance = fetchNuance()
        val serverTimeSec = fetchServerTime()
        val totp = generateTotp(nuance.s, serverTimeSec)

        val tokenUrl = buildString {
            append(TOKEN_URL)
            append("?reason=transport")
            append("&productType=web-player")
            append("&totp=$totp")
            append("&totpServer=$totp")
            append("&totpVer=${nuance.v}")
        }

        val cookieHeader = buildString {
            append("sp_dc=$spDc")
            if (spKey.isNotEmpty()) {
                append("; sp_key=$spKey")
            }
        }

        val body = withContext(Dispatchers.IO) {
            httpGet(tokenUrl, mapOf("Cookie" to cookieHeader))
        }

        val token = json.decodeFromString<SpotifyInternalToken>(body)

        if (token.isAnonymous || token.accessToken.isBlank()) {
            throw Spotify.SpotifyException(
                401,
                "Received anonymous token — sp_dc cookie is invalid or expired",
            )
        }

        token
    }

    private suspend fun fetchNuance(): Nuance = withContext(Dispatchers.IO) {
        // 1. Try raw gist first (fastest, no rate limits)
        try {
            val rawBody = httpGet(RAW_NUANCE_GIST_URL, emptyMap())
            val list = json.decodeFromString<List<Nuance>>(rawBody)
            val best = list.maxByOrNull { it.v }
            if (best != null) return@withContext best
        } catch (_: Exception) {}

        // 2. Try github api gist
        try {
            val body = httpGet(NUANCE_GIST_API_URL, emptyMap())
            val gist = json.decodeFromString<GistFiles>(body)
            val nuancesJson = gist.files.values.firstOrNull()?.content
            if (nuancesJson != null) {
                val list = json.decodeFromString<List<Nuance>>(nuancesJson)
                val best = list.maxByOrNull { it.v }
                if (best != null) return@withContext best
            }
        } catch (_: Exception) {}

        // 3. Fallback to latest embedded nuance
        FALLBACK_NUANCE
    }

    private suspend fun fetchServerTime(): Long = withContext(Dispatchers.IO) {
        try {
            val body = httpGet(SERVER_TIME_URL, emptyMap())
            val response = json.decodeFromString<ServerTimeResponse>(body)
            response.serverTime
        } catch (_: Exception) {
            // Fallback to local system time in seconds
            System.currentTimeMillis() / 1000L
        }
    }

    /**
     * Generates a 6-digit TOTP using HMAC-SHA1 (RFC 6238).
     * @param secret Base32-encoded shared secret
     * @param serverTimeSec Spotify server time in seconds since epoch
     */
    private fun generateTotp(secret: String, serverTimeSec: Long): String {
        val key = base32Decode(secret)
        val interval = 30L
        val timeStep = floor(serverTimeSec.toDouble() / interval).toLong()

        val timeBytes = ByteArray(8)
        var value = timeStep
        for (i in 7 downTo 0) {
            timeBytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(timeBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val code = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val otp = code % 1_000_000
        return otp.toString().padStart(6, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = input.uppercase().replace("=", "")

        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0

        for (c in cleaned) {
            val value = alphabet.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }

        return output.toByteArray()
    }

    private fun httpGet(urlString: String, extraHeaders: Map<String, String>): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Language", "en")
            for ((key, value) in extraHeaders) {
                connection.setRequestProperty(key, value)
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Spotify.SpotifyException(
                    responseCode,
                    "HTTP $responseCode: $errorBody",
                )
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
