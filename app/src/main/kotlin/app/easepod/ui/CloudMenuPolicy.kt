package app.easepod.ui

import app.easepod.plugins.PluginInfo

internal object CloudMenuPolicy {
    fun allowed(plugin: PluginInfo, account: String, capability: String, safeMode: Boolean, accountActive: Boolean): Boolean =
        !safeMode && plugin.enabled && plugin.approved && accountActive &&
            account !in setOf("", "public", "local") && capability in plugin.capabilities
}
