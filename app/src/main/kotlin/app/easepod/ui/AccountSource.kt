package app.easepod.ui

import app.easepod.plugins.PluginAccount
import app.easepod.plugins.PluginAuthSession
import app.easepod.plugins.PluginManager

internal interface AccountSource {
    suspend fun accounts(plugin: String): List<PluginAccount>
    suspend fun beginAuth(plugin: String): PluginAuthSession
    suspend fun pollAuth(plugin: String, session: String): PluginAuthSession
    suspend fun cancelAuth(plugin: String, session: String)
    suspend fun signOut(plugin: String, account: String)
}

internal class PluginAccountSource(private val plugins: PluginManager) : AccountSource {
    override suspend fun accounts(plugin: String) = plugins.accounts(plugin)
    override suspend fun beginAuth(plugin: String) = plugins.beginAuth(plugin)
    override suspend fun pollAuth(plugin: String, session: String) = plugins.pollAuth(plugin, session)
    override suspend fun cancelAuth(plugin: String, session: String) = plugins.cancelAuth(plugin, session)
    override suspend fun signOut(plugin: String, account: String) = plugins.signOut(plugin, account)
}
