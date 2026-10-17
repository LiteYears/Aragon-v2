package com.example.agent.core

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed encryption for provider credentials persisted in SharedPreferences.
 *
 * Values are stored under their existing preference keys with an "enc:v1:" prefix,
 * so legacy plaintext entries are transparently migrated to AES/GCM-encrypted form
 * the first time they are read. Key material never leaves the Android Keystore;
 * the ciphertext blob layout is [12-byte IV][GCM ciphertext+tag], Base64 (NO_WRAP).
 *
 * If the Keystore is unavailable (rare: broken OEM keystore, locked storage),
 * read() still returns the plaintext value and write() falls back to plaintext so
 * the agent remains functional rather than crashing on a credential operation.
 */
object SecureCredentials {

  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val KEY_ALIAS = "aragon_credentials_v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128
  private const val GCM_IV_BYTES = 12
  private const val ENCRYPTED_PREFIX = "enc:v1:"

  /**
   * Reads and, when necessary, migrates a credential. Returns null when absent,
   * blank, or undecryptable (e.g. app data restored onto a new device whose
   * Keystore rotated the key — safe failure, the user re-enters the key).
   */
  fun read(prefs: SharedPreferences, key: String): String? {
    val raw = prefs.getString(key, null)?.trim().orEmpty()
    if (raw.isBlank()) return null

    if (raw.startsWith(ENCRYPTED_PREFIX)) {
      return decrypt(raw.removePrefix(ENCRYPTED_PREFIX))?.takeIf { it.isNotBlank() }
    }

    // Legacy plaintext credential: transparently re-store encrypted.
    encrypt(raw)?.let { encrypted ->
      prefs.edit().putString(key, encrypted).apply()
    }
    return raw
  }

  /**
   * Stages an encrypted write (or removal for blank values) on the given editor.
   * Caller owns apply()/commit() so multiple settings persist atomically.
   */
  fun write(editor: SharedPreferences.Editor, key: String, value: String?) {
    val clean = value?.trim().orEmpty()
    if (clean.isBlank()) {
      editor.remove(key)
      return
    }
    val encrypted = encrypt(clean)
    if (encrypted != null) {
      editor.putString(key, encrypted)
    } else {
      // Keystore unavailable: keep the agent functional with plaintext storage.
      editor.putString(key, clean)
    }
  }

  private fun encrypt(plaintext: String): String? = try {
    val secretKey = getOrCreateSecretKey() ?: return null
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val iv = cipher.iv
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val combined = ByteArray(iv.size + ciphertext.size)
    System.arraycopy(iv, 0, combined, 0, iv.size)
    System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
    ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
  } catch (_: Exception) {
    null
  }

  private fun decrypt(payload: String): String? {
    return try {
      val combined = Base64.decode(payload, Base64.NO_WRAP)
      if (combined.size <= GCM_IV_BYTES) return null
      val iv = combined.copyOfRange(0, GCM_IV_BYTES)
      val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
      val secretKey = getOrCreateSecretKey() ?: return null
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
      String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) {
      null
    }
  }

  private fun getOrCreateSecretKey(): SecretKey? = try {
    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateSecretKey()
  } catch (_: Exception) {
    null
  }

  private fun generateSecretKey(): SecretKey? = try {
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    generator.generateKey()
  } catch (_: Exception) {
    null
  }
}
