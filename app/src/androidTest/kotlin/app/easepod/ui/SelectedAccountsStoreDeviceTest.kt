package app.easepod.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SelectedAccountsStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "selected-accounts-test-${UUID.randomUUID()}"
    private lateinit var preferences: SharedPreferences
    private lateinit var store: SelectedAccountsStore
    private val plugin = "app.easepod.netease"
    private val secondPlugin = "app.example.service"
    private val scope = UUID.randomUUID().toString()
    private val secondScope = UUID.randomUUID().toString()

    @Before fun prepare() {
        preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        store = SelectedAccountsStore(preferences)
    }

    @After fun cleanup() { context.deleteSharedPreferences(preferenceName) }

    @Test fun aNewStoreRestoresOnlyTheHostScopesOfLocallyActiveRegisteredAccounts() {
        store.select(plugin, scope)
        store.select(secondPlugin, secondScope)
        val restored = SelectedAccountsStore(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
            .restore(setOf(plugin, secondPlugin), setOf(plugin to scope, secondPlugin to secondScope))
        assertEquals(mapOf(plugin to scope, secondPlugin to secondScope), restored)
        assertEquals(restored, preferences.all)
    }

    @Test fun anInactiveSelectionIsRemovedAndCannotReturnWhenThatScopeBecomesActiveLater() {
        store.select(plugin, scope)
        assertTrue(store.restore(setOf(plugin), emptySet()).isEmpty())
        assertTrue(SelectedAccountsStore(preferences).restore(setOf(plugin), setOf(plugin to scope)).isEmpty())
        assertFalse(preferences.contains(plugin))
    }

    @Test fun accountScopesAreBoundToTheirRegisteredPlugin() {
        store.select(plugin, scope)
        store.select(secondPlugin, secondScope)
        assertTrue(store.restore(setOf(plugin), setOf(secondPlugin to scope, secondPlugin to secondScope)).isEmpty())
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun signingOutAnotherAccountPreservesTheCurrentSelection() {
        store.select(plugin, scope)
        store.select(plugin, secondScope)
        store.remove(plugin, scope)
        assertEquals(mapOf(plugin to secondScope), store.restore(setOf(plugin), setOf(plugin to secondScope)))
        store.remove(plugin, secondScope)
        assertTrue(SelectedAccountsStore(preferences).restore(setOf(plugin), setOf(plugin to secondScope)).isEmpty())
    }

    @Test fun reservedScopesAndMalformedPreferenceValuesAreDiscarded() {
        assertThrows(IllegalArgumentException::class.java) { store.select(plugin, "public") }
        assertThrows(IllegalArgumentException::class.java) { store.select(plugin, "local") }
        preferences.edit().putInt(plugin, 1).putString(secondPlugin, "public").commit()
        assertTrue(store.restore(setOf(plugin, secondPlugin), setOf(plugin to scope, secondPlugin to "public")).isEmpty())
        assertTrue(preferences.all.isEmpty())
    }
}
