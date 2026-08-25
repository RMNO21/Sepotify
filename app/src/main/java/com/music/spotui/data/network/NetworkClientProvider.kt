package com.music.spotui.data.network

import android.content.Context
import android.util.Log
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Route
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

enum class ProxyType {
    NONE,
    HTTP,
    SOCKS5
}

data class ProxyConfig(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = ""
)

/**
 * Resilient Network Client Provider equipped with:
 * 1. Built-in DNS-over-HTTPS (DoH) via Cloudflare & Google with hardcoded bootstrap IPs
 * 2. In-App SOCKS5 and HTTP Proxy routing with authentication
 * 3. Connection pooling, failover retries, and high-performance timeouts
 */
object NetworkClientProvider {

    private const val TAG = "NetworkClientProvider"

    private var dohDns: Dns? = null
    private var okHttpClient: OkHttpClient? = null

    @Synchronized
    fun getOkHttpClient(context: Context): OkHttpClient {
        if (okHttpClient != null) return okHttpClient!!

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Configure DNS-over-HTTPS (DoH)
        try {
            val appCacheDir = File(context.cacheDir, "doh_cache").apply { mkdirs() }
            val bootstrapClient = OkHttpClient.Builder()
                .cache(okhttp3.Cache(appCacheDir, 10L * 1024 * 1024))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()

            val cloudflareDns = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4")
                )
                .includeIPv6(false)
                .build()

            dohDns = cloudflareDns
            builder.dns(cloudflareDns)
            Log.d(TAG, "DNS-over-HTTPS (Cloudflare / 1.1.1.1) initialized successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize DoH, falling back to system DNS: ${e.message}")
        }

        // Configure In-App Proxy if set in preferences
        val proxyConfig = getProxyConfig(context)
        if (proxyConfig.type != ProxyType.NONE && proxyConfig.host.isNotBlank() && proxyConfig.port > 0) {
            try {
                val pType = if (proxyConfig.type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
                val proxy = Proxy(pType, InetSocketAddress(proxyConfig.host, proxyConfig.port))
                builder.proxy(proxy)

                if (proxyConfig.username.isNotBlank()) {
                    builder.proxyAuthenticator(object : Authenticator {
                        override fun authenticate(route: Route?, response: Response): okhttp3.Request? {
                            val credential = Credentials.basic(proxyConfig.username, proxyConfig.password)
                            return response.request.newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                    })
                }
                Log.d(TAG, "In-app Proxy enabled: ${proxyConfig.type} -> ${proxyConfig.host}:${proxyConfig.port}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure proxy: ${e.message}")
            }
        }

        val client = builder.build()
        okHttpClient = client
        return client
    }

    private const val PREF_PROXY = "proxy_prefs"
    private const val KEY_PROXY_TYPE = "proxy_type"
    private const val KEY_PROXY_HOST = "proxy_host"
    private const val KEY_PROXY_PORT = "proxy_port"
    private const val KEY_PROXY_USER = "proxy_user"
    private const val KEY_PROXY_PASS = "proxy_pass"

    fun getProxyConfig(context: Context): ProxyConfig {
        val sp = context.getSharedPreferences(PREF_PROXY, Context.MODE_PRIVATE)
        val typeStr = sp.getString(KEY_PROXY_TYPE, ProxyType.NONE.name) ?: ProxyType.NONE.name
        val type = runCatching { ProxyType.valueOf(typeStr) }.getOrDefault(ProxyType.NONE)
        val host = sp.getString(KEY_PROXY_HOST, "").orEmpty()
        val port = sp.getInt(KEY_PROXY_PORT, 8080)
        val user = sp.getString(KEY_PROXY_USER, "").orEmpty()
        val pass = sp.getString(KEY_PROXY_PASS, "").orEmpty()
        return ProxyConfig(type, host, port, user, pass)
    }

    fun saveProxyConfig(context: Context, config: ProxyConfig) {
        val sp = context.getSharedPreferences(PREF_PROXY, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_PROXY_TYPE, config.type.name)
            .putString(KEY_PROXY_HOST, config.host)
            .putInt(KEY_PROXY_PORT, config.port)
            .putString(KEY_PROXY_USER, config.username)
            .putString(KEY_PROXY_PASS, config.password)
            .apply()

        // Invalidate current OkHttpClient to apply new proxy settings
        okHttpClient = null
    }
}
