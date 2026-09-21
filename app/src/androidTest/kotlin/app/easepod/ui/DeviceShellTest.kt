package app.easepod.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import app.easepod.MainActivity
import app.easepod.core.AppSettings
import app.easepod.core.WheelKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DeviceShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[AppModel::class.java]
    private lateinit var originalSettings: AppSettings
    private var originalLockQuery: (() -> Boolean)? = null

    @Before fun prepare() {
        originalSettings = runBlocking { model.app.settings.persistedSettings.first() }
        compose.runOnIdle { originalLockQuery = model.lockQuery }
        runBlocking { model.app.settings.update { it.copy(touchGuard = false, safeMode = false, themeId = "silver", reducedMotion = true) } }
        compose.waitUntil { !model.settings.touchGuard && !model.settings.safeMode && model.settings.themeId == "silver" && model.settings.reducedMotion }
        compose.runOnIdle { model.home() }
    }

    @After fun restore() {
        compose.runOnIdle {
            model.lockQuery = originalLockQuery
            model.refreshLock(originalLockQuery?.invoke() ?: false)
            model.home()
        }
        runBlocking { model.app.settings.update { originalSettings } }
    }

    @Test fun rootAndSettingsKeepFrozenOrder() {
        compose.runOnIdle {
            assertEquals(listOf("音乐", "设置", "随机播放歌曲", "正在播放"), model.page().rows.map { it.title })
            model.navigate(Route("settings"))
            assertEquals(listOf("主题", "轮盘", "播放设置", "睡眠定时", "锁屏覆盖", "全屏显示", "防误触", "插件", "本地与存储", "离线音乐", "关于"), model.page().rows.map { it.title })
        }
        compose.onNodeWithContentDescription("中心，确认").assertExists()
    }

    @Test fun touchGuardBlocksRawLcdTapButKeepsWheelAndAccessibility() {
        runBlocking { model.app.settings.update { it.copy(touchGuard = true) } }
        compose.waitUntil { model.settings.touchGuard }
        compose.onNode(hasText("音乐") and hasClickAction()).performTouchInput { click() }
        compose.runOnIdle { assertEquals("home", model.route.id) }
        compose.onNodeWithContentDescription("中心，确认").performTouchInput { click() }
        compose.runOnIdle { assertEquals("music", model.route.id); model.home() }
        compose.onNode(hasText("音乐") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("music", model.route.id) }
    }

    @Test fun inputConsentIsScopedAndBackKeepsSearch() {
        runBlocking { model.app.settings.update { it.copy(touchGuard = true) } }
        compose.waitUntil { model.settings.touchGuard }
        compose.runOnIdle {
            model.navigate(Route("search")); model.beginInput()
            assertEquals("输入期间允许触屏？", model.dialog?.title)
            assertFalse(model.inputException)
            model.rotate(1); model.key(WheelKey.CENTER)
            assertTrue(model.inputException)
            model.inputChanged("query"); model.back()
            assertEquals("search", model.route.id)
            assertFalse(model.inputException)
            assertEquals("query", model.route.draft)
            model.back(); assertEquals("home", model.route.id)
        }
    }

    @Test fun previewCancelNeverPersistsTheme() {
        compose.waitUntil { model.themeList.size >= 3 }
        compose.runOnIdle {
            model.navigate(Route("themes"))
            val row = model.page().rows.indexOfFirst { it.id == "black" }
            assertTrue(row >= 0); model.activate(row)
            assertEquals("black", model.activeThemeId)
            assertEquals("silver", model.settings.themeId)
            model.back(); assertEquals("silver", model.activeThemeId)
        }
    }

    @Test fun backAndHomeWaitForImeToFinishHiding() {
        compose.runOnIdle {
            model.navigate(Route("search"))
            model.inputChanged("preserved")
            model.imeVisibilityChanged(true)
            model.back()
            assertTrue(model.hidingIme)
            assertFalse(model.editing)
            model.back(); model.home(); model.key(WheelKey.CENTER)
            assertEquals("search", model.route.id)
            assertEquals("preserved", model.route.draft)
            model.imeVisibilityChanged(false)
            assertFalse(model.hidingIme)
            model.back()
            assertEquals("home", model.route.id)
        }
    }

    @Test fun lockedSensitiveActionRequiresUnlockAndReconfirmation() {
        compose.runOnIdle {
            model.lockQuery = { true }; model.refreshLock(true)
            var calls = 0
            model.sensitive { calls++ }
            assertEquals(0, calls)
            model.lockQuery = { false }
            model.unlockResult(true)
            assertEquals(0, calls)
            assertEquals("继续操作？", model.dialog?.title)
            model.rotate(1); model.key(WheelKey.CENTER)
            assertEquals(1, calls)
        }
    }

    @Test fun emptyProductionPagesRenderAndReturn() {
        val routes = listOf("music", "songs", "artists", "artist", "albums", "album", "genres", "playlists", "playlist", "coverflow", "history", "now", "queue", "lyrics", "settings", "themes", "wheel-settings", "audio-settings", "sleep", "plugins", "services", "service", "store", "install", "storage", "local-folders", "permission", "downloads", "about")
        routes.forEach { id ->
            compose.runOnIdle { model.navigate(Route(id)) }
            compose.onNodeWithContentDescription("中心，确认").assertExists()
            compose.runOnIdle { assertEquals(id, model.route.id); model.home() }
        }
    }
}
