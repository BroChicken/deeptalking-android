package com.deeptalking.core.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based, authenticated symmetric encryption for the secret part of a backup.
 *
 * The password is never stored. A random salt is fed to PBKDF2-HMAC-SHA256; the derived
 * key encrypts the JSON secret map with AES-256-GCM (random nonce, 128-bit tag). GCM's
 * authentication tag makes a wrong password fail closed instead of returning garbage.
 *
 * Only the secret bundle is encrypted; characters, chat history and TTS settings stay
 * readable in the backup so an import can still inspect the file.
 */
object BackupCipher {

    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class Envelope(
        val version: Int = VERSION,
        val kdf: String = "PBKDF2WithHmacSHA256",
        val iterations: Int = ITERATIONS,
        val cipher: String = "AES/GCM/NoPadding",
        val salt: String,
        val nonce: String,
        val data: String,
    )

    /** Encrypts [secrets] with [password]; returns the JSON envelope to store under `secrets`. */
    fun encrypt(secrets: Map<String, String>, password: String): String {
        require(password.isNotEmpty()) { "backup password must not be empty" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val plain = json.encodeToString(secrets)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return json.encodeToString(
            Envelope(
                salt = Base64.getEncoder().encodeToString(salt),
                nonce = Base64.getEncoder().encodeToString(nonce),
                data = Base64.getEncoder().encodeToString(encrypted),
            ),
        )
    }

    /** Decrypts an envelope; null means a wrong password or a damaged/incompatible payload. */
    fun decrypt(envelopeJson: String, password: String): Map<String, String>? {
        if (password.isEmpty()) return null
        return runCatching {
            val envelope = json.decodeFromString<Envelope>(envelopeJson)
            if (envelope.version != VERSION || envelope.kdf != "PBKDF2WithHmacSHA256" ||
                envelope.cipher != "AES/GCM/NoPadding" || envelope.iterations <= 0
            ) {
                return null
            }
            val salt = Base64.getDecoder().decode(envelope.salt)
            val nonce = Base64.getDecoder().decode(envelope.nonce)
            val data = Base64.getDecoder().decode(envelope.data)
            val key = deriveKey(password, salt, envelope.iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            val plain = cipher.doFinal(data).toString(Charsets.UTF_8)
            json.decodeFromString<Map<String, String>>(plain)
        }.getOrNull()
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
