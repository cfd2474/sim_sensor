package com.taksolutions.simsensor.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Random bearer tokens. Only their SHA-256 is stored, so a database leak doesn't leak tokens. */
object SecureTokens {
    private val random = SecureRandom()

    /** 256 random bits, URL-safe. */
    fun generate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
