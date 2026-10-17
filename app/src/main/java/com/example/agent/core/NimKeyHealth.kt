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
    var lastSuccessAtMs: Long = 0L,
    var lastFailureAtMs: Long = 0L,
    var consecutiveFailures: Int = 0,
    var cooldownUntilMs: Long = 0L,
    var quarantined: Boolean = false,
    var lastFailureKind: ProviderFailureKind? = null
  ) {
    fun isEligible(nowMs: Long): Boolean = !quarantined && nowMs >= cooldownUntilMs
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

  fun recordSuccess(apiKey: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
    val state = stateOf(apiKey)
    state.lastSuccessAtMs = nowMs
    state.consecutiveFailures = 0
    state.cooldownUntilMs = 0L
    state.quarantined = false
    state.lastFailureKind = null
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
    state.lastFailureAtMs = nowMs
    state.consecutiveFailures++
    state.lastFailureKind = kind
    when (kind) {
      ProviderFailureKind.AUTH -> {
        state.quarantined = true
        state.cooldownUntilMs = Long.MAX_VALUE
      }
      ProviderFailureKind.RATE_LIMITED,
      ProviderFailureKind.QUOTA_EXHAUSTED -> {
        val base = retryAfterMs?.takeIf { it > 0L } ?: backoffMs(state.consecutiveFailures)
        state.cooldownUntilMs = nowMs + base.coerceIn(1_000L, MAX_COOLDOWN_MS)
      }
      ProviderFailureKind.TRANSIENT,
      ProviderFailureKind.NETWORK -> {
        if (state.consecutiveFailures >= 2) {
          state.cooldownUntilMs = nowMs + backoffMs(state.consecutiveFailures).coerceIn(1_000L, 30_000L)
        }
      }
      else -> Unit
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
    val exponent = (consecutiveFailures - 1).coerceIn(0, 5)
    val base = 2_000L shl exponent
    val jitter = ThreadLocalRandom.current().nextLong(0L, 1_000L)
    return base + jitter
  }

  companion object {
    const val MAX_COOLDOWN_MS: Long = 5 * 60_000L
  }
}
