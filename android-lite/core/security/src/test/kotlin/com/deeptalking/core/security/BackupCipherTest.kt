package com.deeptalking.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupCipherTest {

    @Test
    fun `secrets round-trip with the same password`() {
        val secrets = mapOf(
            "api_key" to "global-key",
            "api_key_deepseek" to "deepseek-key",
            "tts_token" to "remote-tts-token",
        )
        val envelope = BackupCipher.encrypt(secrets, "correct horse battery staple")
        assertEquals(secrets, BackupCipher.decrypt(envelope, "correct horse battery staple"))
    }

    @Test
    fun `a wrong password fails closed`() {
        val envelope = BackupCipher.encrypt(mapOf("api_key" to "secret"), "right")
        assertNull(BackupCipher.decrypt(envelope, "wrong"))
    }

    @Test
    fun `an empty password is rejected`() {
        runCatching { BackupCipher.encrypt(mapOf("api_key" to "secret"), "") }
            .onSuccess { throw AssertionError("empty password must not encrypt") }
        assertNull(BackupCipher.decrypt("not-json", ""))
    }
}
