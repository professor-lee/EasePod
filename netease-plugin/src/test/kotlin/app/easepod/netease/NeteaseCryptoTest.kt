package app.easepod.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCryptoTest {
    // Generated independently with Node.js crypto, including RSA_NO_PADDING on a 128-byte block.
    @Test fun weapiMatchesIndependentAesAndRsaVector() {
        val encrypted = NeteaseCrypto.weapi("{\"id\":347230,\"text\":\"EasePod\"}", "0123456789abcdef")
        assertEquals("dT4atqUEvwwphLPMdfJqb/M2Ydgq0WvfC6CrczJAp2wlTMfYUh2ji30AqribrSwV", encrypted["params"])
        assertEquals("35701388baf89fed412e11269b9c76625d095ecaf17f03fa018abe19ea2d38b949debf242ee39a71ca1f6cda71b1b86a45aa909ee27f7e78e267d34e732f0de948206c3340a788d0003372183e2f753c1f78b66ac23d134ac1fc9b993156520ea826b8aa89a962d4491b4b8d7e08738e1da9b07aa39bf4a7ef0b1c210728cd52", encrypted["encSecKey"])
    }

    @Test fun eapiMatchesIndependentSignatureAndCipherVector() {
        val encrypted = NeteaseCrypto.eapi("/api/song/detail", "{\"id\":347230,\"text\":\"EasePod\"}")
        assertEquals("7D398AA5036D61F11B22021C618C242421D51F26B6A0246E121BFC7B69A3481FF2E95625DA0822084214272F22C714E50899F7815FA87BAD73C4B9A33C93D575675F4A9256E922A65A9696E4A32B7F71B9BD391635D3EA7789036B0B8E0BF18EEFCB541FB42BBC958B7D8DF1D488CCA1", encrypted["params"])
        assertNotEquals(encrypted, NeteaseCrypto.eapi("/api/song/lyric", "{\"id\":347230,\"text\":\"EasePod\"}"))
    }

    @Test fun productionWeapiUsesANewSecretForEachRequest() {
        val first = NeteaseCrypto.weapi("{}")
        val second = NeteaseCrypto.weapi("{}")
        assertNotEquals(first["params"], second["params"])
        assertNotEquals(first["encSecKey"], second["encSecKey"])
        assertTrue(first.getValue("encSecKey").matches(Regex("[a-f0-9]{256}")))
    }
}
