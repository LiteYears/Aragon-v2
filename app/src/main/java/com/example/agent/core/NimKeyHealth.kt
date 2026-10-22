package com.example.agent.core

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Per-credential health tracker for the NVIDIA NIM key pool.
 *
 * The tracker never stores raw API keys: keys are addressed by a short SHA-256
 * fingerprint so diagnostics and status views stay secret-free.
 *
 * Responsibilities:
 *  - cooldown deadlines after 429/transient failures (honoring Retry-After),
 *  - quarantine of credentials rejected with HTTP 401 until the user changes them,
 *  - bounded exponential backoff with jitter for repeated transient failures,
 *  - concurrency-safe state shared by all in-flight provider calls.
 *
 * State is intentionally in-memory: cooldowns are short-lived operational data.
 * The configured key set itself persists through the ViewModel preferences.
 */
class NimKeyHealthTracker {

  data class KeyHealth(
    val fingerprint: String,
    var lastUsedAtMs: Long = 0L,
    var lastSuccessAtMs: Long = 0L,
    var lastFailureAtMs: Long = 0L,
    var consecutiveFailures: Int = 0,
    var cooldownUntilMs: Long = 0L,
    var degradedUntilMs: Long = 0L,
    var ewmaLatencyMs: Long = 0L,
    var inFlightCount: Int = 0,
    var quarantined: Boolean = false,
    var lastFailureKind: ProviderFailureKind? = null
  ) {
    fun isEligible(nowMs: Long): Boolean = !quarantined && nowMs >= cooldownUntilMs
    fun isDegraded(nowMs: Long): Boolean = nowMs < degradedUntilMs
  }

  /** Secret-free status snapshot for diagnostics and Settings UI. */
  data class KeyStatus(
    val fingerprint: String,
    val quarantined: Boolean,
    val coolingDown: Boolean,
    val cooldownRemainingMs: Long,
    val consecutiveFailures: Int,
    val lastFailureKind: ProviderFailureKind?,
    val lastSuccessAtMs: Long
  )

  private val keyStates = ConcurrentHashMap<String, KeyHealth>()
  private val lock = Any()

  fun fingerprintOf(apiKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(apiKey.trim().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(12)
  }

  fun stateOf(apiKey: String): KeyHealth =
    keyStates.computeIfAbsent(fingerprintOf(apiKey)) { KeyHealth(it) }

  fun isEligible(apiKey: String, nowMs: Long = System.currentTimeMillis()): Boolean =
    stateOf(apiKey).isEligible(nowMs)

  fun markDispatchStart(apiKey: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
    val state = stateOf(apiKey)
    state.lastUsedAtMs = nowMs
    state.inFlightCount = (state.inFlightCount + 1).coerceAtLeast(1)
  }

  fun markDispatchEnd(apiKey: String) = synchronized(lock) {
    val state = stateOf(apiKey)
    state.inFlightCount = (state.inFlightCount - 1).coerceAtLeast(0)
  }

  fun recordSuccess(
    apiKey: String,
    nowMs: Long = System.currentTimeMillis(),
    latencyMs: Long = 0L
  ) = synchronized(lock) {
    val state = stateOf(apiKey)
    state.lastSuccessAtMs = nowMs
    state.consecutiveFailures = 0
    state.cooldownUntilMs = 0L
    state.quarantined = false
    state.lastFailureKind = null
    if (latencyMs > 0L) {
      state.ewmaLatencyMs = if (state.ewmaLatencyMs == 0L) latencyMs else ((state.ewmaLatencyMs * 7 + latencyMs * 3) / 10)
    }
  }

  /**
   * Marks a key as temporarily degraded (soft cooldown) when its SSE stream stalls,
   * gets interrupted mid-generation, or exhibits severe token throttling, so the next
   * turn immediately rotates to a fresh key in the pool.
   */
  fun recordSlowOrInterruptedStream(
    apiKey: String,
    nowMs: Long = System.currentTimeMillis(),
    softCooldownMs: Long = 18_000L
  ) = synchronized(lock) {
    val state = stateOf(apiKey)
    state.degradedUntilMs = maxOf(state.degradedUntilMs, nowMs + softCooldownMs)
  }

  /**
   * Orders all eligible keys in the pool for optimal rotation:
   * 1. Keys not currently in-flight (`inFlightCount == 0`)
   * 2. Keys not in soft-degraded state (`!isDegraded`)
   * 3. Keys with 0 consecutive failures
   * 4. Least-recently dispatched (`lastUsedAtMs` oldest first, tie-broken by round-robin `startIndex`)
   */
  fun selectBestKeyIndices(
    pool: List<String>,
    startIndex: Int,
    nowMs: Long = System.currentTimeMillis()
  ): List<Int> = synchronized(lock) {
    val poolSize = pool.size
    if (poolSize == 0) return emptyList()
    val rotatedOrder = (0 until poolSize).map { (startIndex + it) % poolSize }
    val eligible = rotatedOrder.filter { idx -> stateOf(pool[idx]).isEligible(nowMs) }
    if (eligible.size <= 1) return eligible

    return eligible.sortedWith(
      compareBy<Int> { idx -> if (stateOf(pool[idx]).inFlightCount > 0) 1 else 0 }
        .thenBy { idx -> if (stateOf(pool[idx]).isDegraded(nowMs)) 1 else 0 }
        .thenBy { idx -> stateOf(pool[idx]).consecutiveFailures }
        .thenBy { idx ->
          // Bucket lastUsedAtMs into 2-second windows so keys that haven't been used recently
          // are preferred, while keys unused in the current window follow round-robin order.
          stateOf(pool[idx]).lastUsedAtMs / 2_000L
        }
    )
  }

  /**
   * Records a failure and returns the resulting cooldown deadline.
   * Credentials are only penalized for failures that changing the credential
   * could actually fix (auth, rate limits, transient transport problems).
   * Malformed requests and model-specific problems leave key health untouched.
   */
  fun recordFailure(
    apiKey: String,
    kind: ProviderFailureKind,
    retryAfterMs: Long? = null,
    nowMs: Long = System.currentTimeMillis()
  ): Long = synchronized(lock) {
    val state = stateOf(apiKey)
    when (kind) {
      ProviderFailureKind.AUTH -> {
        state.lastFailureAtMs = nowMs
        state.consecutiveFailures++
        state.lastFailureKind = kind
        state.quarantined = true
        state.cooldownUntilMs = Long.MAX_VALUE
      }
      ProviderFailureKind.RATE_LIMITED,
      ProviderFailureKind.QUOTA_EXHAUSTED -> {
        state.lastFailureAtMs = nowMs
        state.consecutiveFailures++
        state.lastFailureKind = kind
        val base = retryAfterMs?.takeIf { it > 0L } ?: backoffMs(state.consecutiveFailures)
        state.cooldownUntilMs = nowMs + base.coerceIn(1_500L, MAX_COOLDOWN_MS)
      }
      ProviderFailureKind.TRANSIENT,
      ProviderFailureKind.NETWORK -> {
        state.lastFailureAtMs = nowMs
        state.consecutiveFailures++
        state.lastFailureKind = kind
        state.degradedUntilMs = nowMs + 15_000L
        if (state.consecutiveFailures >= 2) {
          state.cooldownUntilMs = nowMs + backoffMs(state.consecutiveFailures).coerceIn(1_500L, 25_000L)
        }
      }
      else -> {
        // Model-specific or request payload errors do not penalize credential health
      }
    }
    state.cooldownUntilMs
  }

  fun statusOf(apiKey: String, nowMs: Long = System.currentTimeMillis()): KeyStatus {
    val state = stateOf(apiKey)
    return KeyStatus(
      fingerprint = state.fingerprint,
      quarantined = state.quarantined,
      coolingDown = !state.quarantined && nowMs < state.cooldownUntilMs,
      cooldownRemainingMs = if (state.quarantined) Long.MAX_VALUE else (state.cooldownUntilMs - nowMs).coerceAtLeast(0L),
      consecutiveFailures = state.consecutiveFailures,
      lastFailureKind = state.lastFailureKind,
      lastSuccessAtMs = state.lastSuccessAtMs
    )
  }

  /** Called whenever the configured key set changes; fresh credentials start healthy. */
  fun reset() = synchronized(lock) { keyStates.clear() }

  private fun backoffMs(consecutiveFailures: Int): Long {
    val exponent = (consecutiveFailures - 1).coerceIn(0, 4)
    val base = 2_000L shl exponent
    val jitter = ThreadLocalRandom.current().nextLong(0L, 800L)
    return base + jitter
  }

  companion object {
    const val MAX_COOLDOWN_MS: Long = 3 * 60_000L
  }
}
