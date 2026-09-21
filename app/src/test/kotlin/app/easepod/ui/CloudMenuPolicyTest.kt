package app.easepod.ui

import app.easepod.plugins.PluginInfo
import org.junit.Assert.*
import org.junit.Test

class CloudMenuPolicyTest {
    @Test fun cloudWriteMenusRequireCapabilityApprovedPluginAndActivePrivateAccount() {
        val plugin = PluginInfo("id", "name", "package", "service", "signature", "1", enabled = true,
            approved = true, capabilities = setOf("library.favorite"))
        fun allowed(p: PluginInfo = plugin, scope: String = "scope", safe: Boolean = false, active: Boolean = true) =
            CloudMenuPolicy.allowed(p, scope, "library.favorite", safe, active)
        assertTrue(allowed())
        assertFalse(allowed(plugin.copy(capabilities = emptySet())))
        assertFalse(allowed(plugin.copy(approved = false)))
        assertFalse(allowed(plugin.copy(enabled = false)))
        assertFalse(allowed(scope = "public")); assertFalse(allowed(scope = "local"))
        assertFalse(allowed(safe = true)); assertFalse(allowed(active = false))
    }
}
