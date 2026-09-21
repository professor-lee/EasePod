package app.easepod.netease

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Cookie
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class EncryptedNeteaseAccountStoreDeviceTest {
    private lateinit var directory: File
    private lateinit var isolatedContext: Context
    private lateinit var keyAlias: String
    private val encryptedFile get() = File(directory, "netease/accounts.enc")

    @Before fun isolateStorageAndKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = UUID.randomUUID().toString()
        directory = File(context.cacheDir, "netease-account-test-$identity").apply { check(mkdirs()) }
        keyAlias = "easepod.netease.test.$identity"
        isolatedContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @After fun removeOnlyTheTestStorageAndKey() {
        if (::directory.isInitialized) check(directory.deleteRecursively())
        if (::keyAlias.isInitialized) KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
    }

    @Test fun realKeystoreRoundTripPreservesAllCookieAttributesAcrossStoreInstances() {
        val accounts = listOf(StoredNeteaseAccount("7", "Account fixture", listOf(
            Cookie.Builder().name("MUSIC_U").value("encrypted-secret-one").domain("music.163.com")
                .path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 86_400_000).build(),
            Cookie.Builder().name("session").value("encrypted-secret-two").hostOnlyDomain("music.163.com")
                .path("/weapi/account").build(),
        )))
        val store = newStore()
        assertTrue(store.load().isEmpty())
        store.save(accounts)
        val firstCiphertext = encryptedFile.readBytes()
        assertFalse(firstCiphertext.toString(Charsets.ISO_8859_1).contains("encrypted-secret-one"))
        assertFalse(firstCiphertext.toString(Charsets.ISO_8859_1).contains("Account fixture"))
        assertEquals(accounts, newStore().load())
        assertNotNull(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(keyAlias, null))
        store.save(accounts)
        assertFalse("AES-GCM must use a fresh nonce", firstCiphertext.contentEquals(encryptedFile.readBytes()))
        assertEquals(accounts, newStore().load())
    }

    @Test fun modifiedCiphertextFailsAuthenticationAndCannotReturnAnyAccount() {
        newStore().save(listOf(account()))
        val bytes = encryptedFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        encryptedFile.writeBytes(bytes)
        assertThrows(AEADBadTagException::class.java) { newStore().load() }
    }

    @Test fun aMissingKeyCannotSilentlyReplaceCredentialsWithAnEmptyStore() {
        newStore().save(listOf(account()))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
        assertThrows(IllegalStateException::class.java) { newStore().load() }
        assertTrue(encryptedFile.isFile)
    }

    @Test fun savingNoAccountsDeletesCiphertextAndAtomicBackupFiles() {
        val store = newStore()
        store.save(listOf(account()))
        assertTrue(encryptedFile.isFile)
        File(encryptedFile.path + ".bak").writeBytes(encryptedFile.readBytes())
        File(encryptedFile.path + ".new").writeBytes(encryptedFile.readBytes())
        store.save(emptyList())
        assertFalse(encryptedFile.exists())
        assertFalse(File(encryptedFile.path + ".bak").exists())
        assertFalse(File(encryptedFile.path + ".new").exists())
        assertTrue(newStore().load().isEmpty())
        store.save(listOf(account()))
        assertEquals("7", newStore().load().single().id)
    }

    private fun newStore() = EncryptedNeteaseAccountStore(isolatedContext, keyAlias)
    private fun account() = StoredNeteaseAccount("7", "Fixture", listOf(
        Cookie.Builder().name("MUSIC_U").value("fixture-only-secret").domain("music.163.com").path("/").secure().build(),
    ))
}
