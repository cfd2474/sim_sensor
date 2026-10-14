package com.taksolutions.simsensor.server.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

interface PasswordHasher {
    fun hash(password: String): String
    fun verify(password: String, encoded: String): Boolean
}

/**
 * Argon2id, stored in the standard PHC string format
 * `$argon2id$v=19$m=<KiB>,t=<iterations>,p=<lanes>$<salt>$<hash>`.
 * Defaults follow the OWASP Password Storage Cheat Sheet (m=19 MiB, t=2, p=1).
 * Verification reads the parameters from the stored string, so they can be raised later
 * without breaking existing hashes.
 */
class Argon2PasswordHasher(
    private val memoryKib: Int = 19_456,
    private val iterations: Int = 2,
    private val parallelism: Int = 1,
    private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {

    override fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, memoryKib, iterations, parallelism, HASH_BYTES)
        return "\$argon2id\$v=19\$m=$memoryKib,t=$iterations,p=$parallelism\$${b64(salt)}\$${b64(hash)}"
    }

    override fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
        if (parts.size != 6 || parts[1] != "argon2id" || parts[2] != "v=19") return false
        val params = parts[3].split(',').associate { kv -> kv.substringBefore('=') to kv.substringAfter('=').toIntOrNull() }
        val m = params["m"] ?: return false
        val t = params["t"] ?: return false
        val p = params["p"] ?: return false
        val salt = runCatching { B64_DECODER.decode(parts[4]) }.getOrNull() ?: return false
        val expected = runCatching { B64_DECODER.decode(parts[5]) }.getOrNull() ?: return false
        val actual = derive(password, salt, m, t, p, expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, m: Int, t: Int, p: Int, length: Int): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(m)
            .withIterations(t)
            .withParallelism(p)
            .withSalt(salt)
            .build()
        val out = ByteArray(length)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password.toCharArray(), out)
        return out
    }

    private fun b64(bytes: ByteArray): String = B64_ENCODER.encodeToString(bytes)

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        val B64_ENCODER: Base64.Encoder = Base64.getEncoder().withoutPadding()
        val B64_DECODER: Base64.Decoder = Base64.getDecoder()
    }
}
