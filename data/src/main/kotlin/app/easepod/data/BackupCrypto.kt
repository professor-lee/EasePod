package app.easepod.data

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

internal object BackupCrypto {
    const val MAX_CONTAINER_BYTES = 16 * 1024 * 1024
    const val MAX_PLAIN_BYTES = 12 * 1024 * 1024
    private val magic = byteArrayOf(0x45, 0x50, 0x42, 0x4b, 0x01, 0x0d, 0x0a, 0x00)
    private const val HEADER_BYTES = 48

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(plain.size <= MAX_PLAIN_BYTES) { "备份内容超过 12 MiB 上限" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(magic).putInt(65536).putInt(3).putInt(1).put(salt).put(nonce).array()
        val key = derive(passphrase, salt, 65536, 3, 1)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(plain)
        } finally { key.fill(0) }
    }

    fun decrypt(bytes: ByteArray, passphrase: CharArray): ByteArray {
        require(bytes.size in (HEADER_BYTES + 16)..MAX_CONTAINER_BYTES) { "备份文件大小不合法" }
        val header = bytes.copyOfRange(0, HEADER_BYTES)
        val input = ByteBuffer.wrap(header)
        require(ByteArray(magic.size).also(input::get).contentEquals(magic)) { "不支持的备份格式或版本" }
        val memory = input.int
        val iterations = input.int
        val parallelism = input.int
        require(memory in 32768..131072 && iterations in 2..6 && parallelism in 1..4) { "备份密钥参数超出允许范围" }
        val salt = ByteArray(16).also(input::get)
        val nonce = ByteArray(12).also(input::get)
        val key = derive(passphrase, salt, memory, iterations, parallelism)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES).also {
                require(it.size <= MAX_PLAIN_BYTES) { "备份内容超过上限" }
            }
        } finally { key.fill(0) }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, memory: Int, iterations: Int, parallelism: Int): ByteArray {
        require(passphrase.size in 8..1024) { "备份口令需为 8 至 1024 个字符" }
        val buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(passphrase))
        val password = ByteArray(buffer.remaining()).also(buffer::get)
        return try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
                .withMemoryAsKB(memory).withIterations(iterations).withParallelism(parallelism).build()
            ByteArray(32).also { key -> Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, key) }
        } finally { password.fill(0); if (buffer.hasArray()) buffer.array().fill(0) }
    }
}
