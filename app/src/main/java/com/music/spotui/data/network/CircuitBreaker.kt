package com.music.spotui.data.network

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Circuit Breaker & Exponential Backoff for audio providers (Deezer, YouTube, Lossless, Saavn, Piped).
 * When a provider fails with 429 / 503 / timeout or repeated errors:
 * - Trips the circuit breaker for 60 seconds.
 * - Prevents cascading timeouts and fast-fails directly to next tier in the cascade.
 * - Applies exponential backoff with jitter on retries.
 */
object CircuitBreaker {

    private const val TAG = "CircuitBreaker"
    private const val TRIP_DURATION_MS = 60_000L
    private const val ERROR_THRESHOLD = 3

    private data class ProviderState(
        var failureCount: Int = 0,
        var lastFailureTime: Long = 0L,
        var circuitOpenUntil: Long = 0L
    )

    private val providerStates = ConcurrentHashMap<String, ProviderState>()

    /**
     * Check if provider is currently available to receive requests.
     */
    fun isAvailable(providerName: String): Boolean {
        val state = providerStates[providerName] ?: return true
        val now = System.currentTimeMillis()
        if (state.circuitOpenUntil > now) {
            Log.d(TAG, "Circuit OPEN for $providerName (cooldown remaining: ${(state.circuitOpenUntil - now) / 1000}s)")
            return false
        }
        return true
    }

    /**
     * Record a successful response from a provider.
     */
    fun recordSuccess(providerName: String) {
        val state = providerStates[providerName] ?: return
        state.failureCount = 0
        state.circuitOpenUntil = 0L
    }

    /**
     * Record a failure (429, 503, timeout, network error).
     */
    fun recordFailure(providerName: String, isRateLimited: Boolean = false) {
        val state = providerStates.getOrPut(providerName) { ProviderState() }
        val now = System.currentTimeMillis()
        state.lastFailureTime = now
        state.failureCount++

        if (isRateLimited || state.failureCount >= ERROR_THRESHOLD) {
            state.circuitOpenUntil = now + TRIP_DURATION_MS
            Log.w(TAG, "Circuit breaker TRIPPED for $providerName for ${TRIP_DURATION_MS / 1000}s (failures: ${state.failureCount}, rateLimited: $isRateLimited)")
        }
    }

    /**
     * Calculate backoff delay in ms: T_wait = 2^retryCount * 500ms ± Jitter
     */
    fun calculateBackoffMs(retryCount: Int): Long {
        val baseDelay = (2.0.pow(retryCount.toDouble()) * 500.0).toLong()
        val capped = min(baseDelay, 8000L)
        val jitter = Random.nextLong(-200L, 200L)
        return (capped + jitter).coerceAtLeast(100L)
    }

    /**
     * Manually reset all circuit breakers (e.g., when user reconnects or toggles settings).
     */
    fun resetAll() {
        providerStates.clear()
    }
}
