package app.easepod.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MusicSourceStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "music-source-test-${UUID.randomUUID()}"
    private lateinit var preferences: SharedPreferences
    private lateinit var store: MusicSourceStore
    private val selection = MusicSourceSelection("app.easepod.netease", UUID.randomUUID().toString())

    @Before fun prepare() {
        preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        store = MusicSourceStore(preferences)
    }

    @After fun cleanup() { context.deleteSharedPreferences(preferenceName) }

    @Test fun anUnconfiguredStoreIsDistinctFromAnExplicitLocalSelection() {
        assertFalse(store.hasSelection())
        assertNull(store.read())
        assertFalse(store.hasSelection())
        store.select(null)
        val recreated = MusicSourceStore(preferences)
        assertTrue(recreated.hasSelection())
        assertNull(recreated.read())
    }

    @Test fun aCloudSelectionSurvivesRecreationWithOnlyPluginAndAccountReferences() {
        store.select(selection)
        val recreated = MusicSourceStore(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
        assertTrue(recreated.hasSelection())
        assertEquals(selection, recreated.read())
        assertEquals(mapOf("configured" to true, "pluginId" to selection.pluginId, "accountScope" to selection.accountScope), preferences.all)
    }

    @Test fun switchingAccountsAndReturningToLocalClearsThePreviousReference() {
        store.select(selection)
        val second = MusicSourceSelection("app.example.service", UUID.randomUUID().toString())
        store.select(second)
        assertEquals(second, MusicSourceStore(preferences).read())
        store.select(null)
        assertNull(MusicSourceStore(preferences).read())
        assertEquals(mapOf("configured" to true), preferences.all)
    }

    @Test fun malformedOrReservedSelectionsAreRejectedWithoutChangingTheCurrentSource() {
        store.select(selection)
        val invalid = listOf(
            selection.copy(pluginId = "ab"),
            selection.copy(pluginId = "1app.service"),
            selection.copy(pluginId = "app/service"),
            selection.copy(pluginId = "a".repeat(129)),
            selection.copy(accountScope = ""),
            selection.copy(accountScope = "public"),
            selection.copy(accountScope = "local"),
            selection.copy(accountScope = "a b"),
            selection.copy(accountScope = "a\nb"),
            selection.copy(accountScope = "a".repeat(129)),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { store.select(value) }
            assertEquals(selection, store.read())
        }
    }

    @Test fun malformedStoredValuesFallBackToExplicitLocalAndCannotTriggerMigration() {
        val corruptions: List<SharedPreferences.Editor.() -> Unit> = listOf(
            { putInt("pluginId", 42) },
            { putString("pluginId", "app/service") },
            { putBoolean("accountScope", false) },
            { putString("accountScope", "public") },
            { putString("accountScope", "local") },
            { remove("pluginId") },
            { remove("accountScope") },
            { remove("configured") },
            { putBoolean("configured", false) },
            { putString("configured", "true") },
        )
        corruptions.forEach { corrupt ->
            store.select(selection)
            preferences.edit().apply(corrupt).commit()
            val recreated = MusicSourceStore(preferences)
            assertTrue(recreated.hasSelection())
            assertNull(recreated.read())
            assertTrue(recreated.hasSelection())
            assertEquals(mapOf("configured" to true), preferences.all)
            assertNull(MusicSourceStore(preferences).read())
        }
    }
}
