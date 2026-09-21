package app.easepod.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import app.easepod.MainActivity
import app.easepod.core.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class WindowSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[AppModel::class.java]
    private lateinit var originalSettings: AppSettings
    private var originalLockQuery: (() -> Boolean)? = null
    private var originalEffectHandler: ((SystemEffect) -> Unit)? = null

    @Before fun prepare() {
        runBlocking { originalSettings = model.app.settings.persistedSettings.first() }
        compose.runOnIdle {
            originalLockQuery = model.lockQuery
            originalEffectHandler = model.effectHandler
            model.home()
        }
        runBlocking { model.app.settings.update { it.copy(fullScreen = false, lockScreenOverlay = true) } }
        compose.waitUntil { !model.settings.fullScreen && model.settings.lockScreenOverlay }
    }

    @After fun restore() {
        compose.runOnIdle {
            model.lockQuery = originalLockQuery
            model.effectHandler = originalEffectHandler
            model.refreshLock(originalLockQuery?.invoke() ?: false)
            model.home()
        }
        runBlocking { model.app.settings.update { originalSettings } }
    }

    @Test fun fullScreenHidesOnlyStatusBarAndSurvivesRecreation() {
        compose.waitUntil(5_000) { barVisible(WindowInsetsCompat.Type.statusBars()) == true }
        val navigationVisible = barVisible(WindowInsetsCompat.Type.navigationBars())
        capture("fullscreen-off")
        compose.runOnIdle {
            model.navigate(Route("settings"))
            model.activate(model.page().rows.indexOfFirst { it.title == "全屏显示" })
        }
        compose.waitUntil(5_000) { model.settings.fullScreen && barVisible(WindowInsetsCompat.Type.statusBars()) == false }
        assertEquals(navigationVisible, barVisible(WindowInsetsCompat.Type.navigationBars()))
        capture("fullscreen-on")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { model.settings.fullScreen && barVisible(WindowInsetsCompat.Type.statusBars()) == false }
        compose.runOnIdle {
            model.navigate(Route("settings"))
            assertEquals(true, model.page().rows.first { it.title == "全屏显示" }.toggle)
            model.activate(model.page().rows.indexOfFirst { it.title == "全屏显示" })
        }
        compose.waitUntil(5_000) { !model.settings.fullScreen && barVisible(WindowInsetsCompat.Type.statusBars()) == true }
        assertEquals(navigationVisible, barVisible(WindowInsetsCompat.Type.navigationBars()))
    }

    @Test fun overlayUnlockHasNoDialogAndDoesNotResumeSensitiveAction() {
        compose.runOnIdle {
            val effects = mutableListOf<SystemEffect>()
            model.lockQuery = { true }
            model.refreshLock(true)
            model.effectHandler = { effects += it }
            var sensitiveCalls = 0
            model.sensitive { sensitiveCalls++ }
            assertEquals("解锁后继续", model.dialog?.title)
            model.unlockOverlay()
            assertEquals(listOf(SystemEffect.Unlock), effects)
            assertNull(model.dialog)
            model.lockQuery = { false }
            model.unlockResult(true)
            assertFalse(model.locked)
            assertNull(model.dialog)
            assertEquals(0, sensitiveCalls)
            model.unlockOverlay()
            assertEquals(1, effects.size)
        }
        runBlocking { model.app.settings.update { it.copy(lockScreenOverlay = false) } }
        compose.waitUntil { !model.settings.lockScreenOverlay }
        compose.runOnIdle {
            model.lockQuery = { true }
            model.refreshLock(true)
            var calls = 0
            model.effectHandler = { calls++ }
            model.unlockOverlay()
            assertEquals(0, calls)
        }
    }

    private fun barVisible(type: Int): Boolean? {
        var visible: Boolean? = null
        compose.activityRule.scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(type) }
        return visible
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = requireNotNull(compose.activity.getExternalFilesDir("verification"))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
