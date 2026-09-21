package app.easepod.netease

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedNeteaseAccountStore(context: Context, private val keyAlias: String = KEY_ALIAS) : NeteaseAccountStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "netease/accounts.enc"))
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized override fun load(): List<StoredNeteaseAccount> {
        if (!file.baseFile.exists()) return emptyList()
        val encrypted = file.openRead().use { it.readNBytes(MAX_BYTES + 1) }
        require(encrypted.size in 30..MAX_BYTES && encrypted[0] == 1.toByte())
        val key = keyStore.getKey(keyAlias, null) as? SecretKey ?: error("Account key unavailable")
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
            updateAAD(AAD)
            doFinal(encrypted, 13, encrypted.size - 13)
        }
        try {
            val json = JSONObject(plain.toString(Charsets.UTF_8))
            require(json.getInt("version") == 1)
            val records = json.getJSONArray("accounts")
            require(records.length() <= 4)
            return List(records.length()) { index ->
                val record = records.getJSONObject(index)
                val values = record.getJSONArray("cookies")
                require(values.length() <= 128)
                StoredNeteaseAccount(record.getString("id"), record.getString("name"), List(values.length()) { cookieIndex ->
                    val item = values.getJSONObject(cookieIndex)
                    Cookie.Builder().name(item.getString("name")).value(item.getString("value"))
                        .path(item.getString("path")).apply {
                            if (item.getBoolean("hostOnly")) hostOnlyDomain(item.getString("domain")) else domain(item.getString("domain"))
                            if (item.getBoolean("persistent")) expiresAt(item.getLong("expiresAt"))
                            if (item.getBoolean("secure")) secure()
                            if (item.getBoolean("httpOnly")) httpOnly()
                        }.build()
                })
            }
        } finally { plain.fill(0) }
    }

    @Synchronized override fun save(accounts: List<StoredNeteaseAccount>) {
        require(accounts.size <= 4)
        if (accounts.isEmpty()) { file.delete(); return }
        val records = JSONArray()
        accounts.forEach { account ->
            require(account.cookies.size <= 128)
            val cookies = JSONArray()
            account.cookies.forEach { cookie -> cookies.put(JSONObject().put("name", cookie.name).put("value", cookie.value)
                .put("domain", cookie.domain).put("path", cookie.path).put("hostOnly", cookie.hostOnly)
                .put("persistent", cookie.persistent).put("expiresAt", cookie.expiresAt)
                .put("secure", cookie.secure).put("httpOnly", cookie.httpOnly)) }
            records.put(JSONObject().put("id", account.id).put("name", account.name).put("cookies", cookies))
        }
        val plain = JSONObject().put("version", 1).put("accounts", records).toString().toByteArray(Charsets.UTF_8)
        try {
            require(plain.size < MAX_BYTES - 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(AAD) }
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            file.baseFile.parentFile!!.mkdirs()
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        } finally { plain.fill(0) }
    }

    private fun key(): SecretKey = (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "easepod.netease.accounts.v1"
        private const val MAX_BYTES = 256 * 1024
        private val AAD = "EasePod:NeteaseAccounts:1".toByteArray(Charsets.US_ASCII)
    }
}
