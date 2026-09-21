package app.easepod.ui

import android.content.Context
import android.content.SharedPreferences

internal data class MusicSourceSelection(val pluginId: String, val accountScope: String)

internal class MusicSourceStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("current-music-source", Context.MODE_PRIVATE))

    fun hasSelection(): Boolean = preferences.all.isNotEmpty()

    fun read(): MusicSourceSelection? {
        val stored = preferences.all
        if (stored.isEmpty()) return null
        if (stored == mapOf("configured" to true)) return null
        val plugin = stored["pluginId"] as? String
        val scope = stored["accountScope"] as? String
        if (stored.keys == setOf("configured", "pluginId", "accountScope") &&
            stored["configured"] == true && plugin != null && scope != null && valid(plugin, scope)) {
            return MusicSourceSelection(plugin, scope)
        }
        // A rejected selection stays local instead of triggering legacy-account migration again.
        select(null)
        return null
    }

    fun select(selection: MusicSourceSelection?) {
        require(selection == null || valid(selection.pluginId, selection.accountScope))
        preferences.edit().clear().putBoolean("configured", true).apply {
            if (selection != null) {
                putString("pluginId", selection.pluginId)
                putString("accountScope", selection.accountScope)
            }
        }.apply()
    }

    private fun valid(plugin: String, scope: String): Boolean =
        plugin.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{2,127}")) &&
            scope.length in 1..128 && scope !in setOf("public", "local") &&
            scope.none { it.isWhitespace() || it.isISOControl() }
}
