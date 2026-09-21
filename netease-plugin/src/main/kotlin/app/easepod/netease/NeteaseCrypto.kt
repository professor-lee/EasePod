package app.easepod.netease

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object NeteaseCrypto {
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val IV = "0102030405060708"
    private const val PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
    private val random = SecureRandom()
    private val rsa: RSAPublicKey by lazy {
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))
        ) as RSAPublicKey
    }

    fun weapi(json: String): Map<String, String> = weapi(json, buildString(16) {
        repeat(16) { append(BASE62[random.nextInt(BASE62.length)]) }
    })

    internal fun weapi(json: String, secret: String): Map<String, String> {
        require(secret.length == 16 && secret.all { it in BASE62 })
        val first = cbcBase64(json, PRESET_KEY)
        val encryptedSecret = BigInteger(1, secret.reversed().toByteArray(Charsets.US_ASCII))
            .modPow(rsa.publicExponent, rsa.modulus).toString(16)
            .padStart((rsa.modulus.bitLength() + 7) / 8 * 2, '0')
        return mapOf("params" to cbcBase64(first, secret), "encSecKey" to encryptedSecret)
    }

    fun eapi(path: String, json: String): Map<String, String> {
        val digest = MessageDigest.getInstance("MD5")
            .digest("nobody${path}use${json}md5forencrypt".toByteArray(Charsets.UTF_8)).hex()
        val plaintext = "$path-36cd479b6b5-$json-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.US_ASCII), "AES"))
        return mapOf("params" to cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).hex(uppercase = true))
    }

    private fun cbcBase64(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.US_ASCII)))
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private fun ByteArray.hex(uppercase: Boolean = false): String {
        val digits = if (uppercase) "0123456789ABCDEF" else "0123456789abcdef"
        return buildString(size * 2) {
            for (byte in this@hex) {
                append(digits[(byte.toInt() and 0xff) ushr 4])
                append(digits[byte.toInt() and 0x0f])
            }
        }
    }
}
