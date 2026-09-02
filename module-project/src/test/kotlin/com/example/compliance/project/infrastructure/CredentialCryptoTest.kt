package com.example.compliance.project.infrastructure

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CredentialCryptoTest {
    private val crypto = CredentialCrypto("0123456789abcdef0123456789abcdef")

    @Test
    fun `encrypt then decrypt round-trips and ciphertext differs`() {
        val cipher = crypto.encrypt("my-git-token")
        assertNotEquals("my-git-token", cipher)
        assertEquals("my-git-token", crypto.decrypt(cipher))
    }
}
