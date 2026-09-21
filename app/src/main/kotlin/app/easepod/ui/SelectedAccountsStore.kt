package app.easepod.ui

import android.content.Context
import android.content.SharedPreferences

internal class SelectedAccountsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("selected-service-accounts", Context.MODE_PRIVATE))

    fun restore(registeredPlugins: Set<String>, activeScopes: Set<Pair<String, String>>): Map<String, String> {
        val stored = preferences.all
        val restored = stored.mapNotNull { (plugin, value) ->
            val scope = value as? String
            if (plugin in registeredPlugins && scope != null && validScope(scope) && (plugin to scope) in activeScopes)
                plugin to scope else null
        }.toMap()
        val removed = stored.keys - restored.keys
        if (removed.isNotEmpty()) preferences.edit().apply { removed.forEach(::remove) }.apply()
        return restored
    }

    fun select(plugin: String, scope: String) {
        require(plugin.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{2,127}")) && validScope(scope))
        preferences.edit().putString(plugin, scope).apply()
    }

    fun remove(plugin: String, scope: String? = null) {
        if (scope == null || preferences.all[plugin] == scope) preferences.edit().remove(plugin).apply()
    }

    private fun validScope(scope: String) = scope.length in 1..128 && scope !in setOf("public", "local") && scope.none { it.isWhitespace() || it.isISOControl() }
}
