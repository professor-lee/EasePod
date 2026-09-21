package app.easepod.plugins

import org.junit.Assert.*
import org.junit.Test

class BundledMusicPluginTest {
    private val plugin = BundledMusicPlugin("app.easepod.netease", "Netease", "app.easepod.netease.NeteaseMusicService", emptySet(), emptySet())

    @Test fun firstRegistrationIsEnabledWithAStableSourceIdentity() {
        val first = plugin.registration("app.easepod", "signature", null)
        val recreated = plugin.registration("app.easepod", "signature", null)
        assertTrue(first.enabled)
        assertEquals("signature", first.approvedSignature)
        assertEquals(first.sourceId, recreated.sourceId)
        assertTrue(first.sourceId.startsWith("builtin."))
    }

    @Test fun appUpdatesKeepDisabledStateSourceIdentityAndQuarantine() {
        val original = plugin.registration("app.easepod", "old-signature", null)
            .copy(enabled = false, sourceId = "persisted-source", quarantined = true)
        val updated = plugin.registration("app.easepod", "new-signature", original)
        assertFalse(updated.enabled)
        assertEquals(original.sourceId, updated.sourceId)
        assertTrue(updated.quarantined)
        assertEquals("new-signature", updated.approvedSignature)
    }

    @Test fun anExternalPluginCannotDonateItsSourceIdentityToABundledService() {
        val external = PluginRecord(plugin.id, "other.package", "OtherService", "external", sourceId = "external-source")
        val registered = plugin.registration("app.easepod", "signature", external)
        assertNotEquals(external.sourceId, registered.sourceId)
        assertEquals("app.easepod", registered.packageName)
        assertTrue(registered.enabled)
    }
}
