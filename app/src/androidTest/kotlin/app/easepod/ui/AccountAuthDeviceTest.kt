package app.easepod.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.toSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import app.easepod.MainActivity
import app.easepod.core.AppSettings
import app.easepod.core.WheelKey
import app.easepod.plugins.PluginAccount
import app.easepod.plugins.PluginAuthSession
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AccountAuthDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AppModel
    private lateinit var originalSettings: AppSettings
    private lateinit var originalSource: AccountSource
    private lateinit var originalSelectedAccountsStore: SelectedAccountsStore
    private lateinit var originalMusicSourceStore: MusicSourceStore
    private lateinit var originalSelectedAccounts: Map<String, String>
    private var originalMusicSource: MusicSourceSelection? = null
    private lateinit var preferences: SharedPreferences
    private val preferenceName = "account-auth-test-${UUID.randomUUID()}"
    private var originalLock: (() -> Boolean)? = null
    private var originalEnabled = true
    private val source = ControlledAccounts()
    private val plugin = "app.easepod.netease"

    @Before fun prepare() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppModel::class.java] }
        runBlocking {
            model.app.awaitStartupResources()
            assumeTrue("网易云插件未安装，跳过可选插件账号测试", model.app.plugins.plugins.value.any { it.id == plugin })
            originalSettings = model.app.settings.persistedSettings.first()
            originalEnabled = model.app.plugins.plugins.value.single { it.id == plugin }.enabled
            model.app.settings.update { it.copy(touchGuard = true, safeMode = false, reducedMotion = true, clickSound = false, haptics = false) }
            model.app.plugins.setEnabled(plugin, true)
        }
        compose.waitUntil(10_000) { model.library.ready && model.settings.touchGuard && !model.settings.safeMode && model.pluginList.any { it.id == plugin && it.enabled } }
        compose.runOnIdle {
            originalSource = model.accountSource; originalLock = model.lockQuery
            originalSelectedAccountsStore = model.selectedAccountsStore
            originalMusicSourceStore = model.musicSourceStore
            originalSelectedAccounts = model.selectedAccounts.toMap()
            originalMusicSource = model.musicSource
            preferences = compose.activity.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            model.selectedAccountsStore = SelectedAccountsStore(preferences)
            model.musicSourceStore = MusicSourceStore(compose.activity.getSharedPreferences("$preferenceName-source", Context.MODE_PRIVATE))
            model.selectedAccounts.clear()
            model.selectMusicSource(null)
            model.accountSource = source; model.lockQuery = { false }; model.refreshLock(false); model.home()
            model.navigate(Route("service", key = plugin)); model.navigate(Route("login", key = plugin))
        }
    }

    @After fun restore() {
        if (!::originalSource.isInitialized) return
        compose.runOnIdle {
            model.home(); model.accountSource = originalSource; model.lockQuery = originalLock
            model.selectedAccounts.clear(); model.selectedAccounts.putAll(originalSelectedAccounts)
            model.selectMusicSource(originalMusicSource)
            model.selectedAccountsStore = originalSelectedAccountsStore
            model.musicSourceStore = originalMusicSourceStore
            compose.activity.deleteSharedPreferences(preferenceName)
            compose.activity.deleteSharedPreferences("$preferenceName-source")
            model.refreshLock(originalLock?.invoke() ?: false)
        }
        runBlocking {
            model.app.plugins.setEnabled(plugin, originalEnabled)
            model.app.settings.update { originalSettings }
        }
    }

    @Test fun canonicalQrStatesCompleteLoginAndSelectTheVerifiedAccount() {
        choose("继续授权")
        compose.waitUntil { model.authStatus == "等待扫码" }
        compose.runOnIdle { assertNotNull(model.authSession?.qrContent) }
        poll(0).complete(source.session("WaitingConfirm"))
        compose.waitUntil { model.authStatus == "等待确认" }
        source.signedIn = true
        poll(1).complete(source.session("SignedIn").copy(account = source.account))
        compose.waitUntil { model.authStatus == "授权成功" && model.accounts.isNotEmpty() }
        compose.runOnIdle {
            assertNull(model.authSession)
            assertTrue(source.cancelled.isEmpty())
            assertEquals("当前账号", model.page().rows.single { it.title == source.account.displayName }.detail)
            assertEquals(source.account.scope, restoredSelection())
            model.back()
        }
        choose("搜索音乐")
        compose.runOnIdle { assertEquals(source.account.scope, model.route.accountScope); assertEquals(2, source.polls.size) }
    }

    @Test fun leavingAnActiveQrSessionCancelsItAndClearsTheQr() {
        choose("继续授权")
        compose.waitUntil { model.authStatus == "等待扫码" }
        compose.runOnIdle { model.home() }
        compose.waitUntil { source.cancelled == listOf("auth-session") }
        compose.runOnIdle { assertNull(model.authSession); assertEquals("", model.authStatus) }
    }

    @Test fun qrFitsTheLcdWithoutOverlappingTheMenuAndDecodesFromTheScreen() {
        showQr(largeText = false, fullScreen = false)
        assertVisibleAndScannableQr()
    }

    @Test fun qrRemainsVisibleAndScannableWithLargeTextAndFullScreen() {
        showQr(largeText = true, fullScreen = true)
        assertVisibleAndScannableQr()
    }

    @Test fun bundledServiceDetailsDoNotOfferExternalTrustOrUninstallActions() {
        compose.runOnIdle {
            model.navigate(Route("plugin", key = plugin))
            val titles = model.page().rows.map { it.title }
            assertTrue("禁用插件" in titles)
            assertTrue("重新连接" in titles)
            assertFalse("在插件中批准 EasePod" in titles)
            assertFalse("卸载插件" in titles)
            assertNull(model.app.plugins.hostApprovalIntent(plugin))
            assertThrows(IllegalArgumentException::class.java) { model.app.plugins.uninstallIntent(plugin) }
        }
    }

    @Test fun manualSelectionPersistsAndExpiredAccountsCannotBeSelectedAgain() {
        source.signedIn = true
        choose("刷新账号")
        compose.waitUntil { model.accounts.size == 1 }
        choose(source.account.displayName)
        compose.runOnIdle {
            model.dialog!!.rows.single { it.title == "使用此账号" }.action()
            assertEquals(source.account.scope, restoredSelection())
        }
        source.accountState = "Expired"
        choose("刷新账号")
        compose.waitUntil { model.accounts.singleOrNull()?.state == "Expired" }
        choose(source.account.displayName)
        compose.runOnIdle {
            assertFalse(model.dialog!!.rows.any { it.title == "使用此账号" })
            assertNull(restoredSelection())
            model.cancelDialog()
        }
        source.accountState = "SignedOut"
        choose("刷新账号")
        compose.waitUntil { model.accounts.singleOrNull()?.state == "SignedOut" }
        choose(source.account.displayName)
        compose.runOnIdle { assertFalse(model.dialog!!.rows.any { it.title == "使用此账号" }) }
    }

    private fun restoredSelection() = SelectedAccountsStore(preferences)
        .restore(setOf(plugin), setOf(plugin to source.account.scope))[plugin]

    private fun showQr(largeText: Boolean, fullScreen: Boolean) {
        runBlocking { model.app.settings.update { it.copy(largeText = largeText, fullScreen = fullScreen) } }
        compose.waitUntil(5_000) { model.settings.largeText == largeText && model.settings.fullScreen == fullScreen }
        compose.waitUntil(5_000) {
            var statusBarVisible: Boolean? = null
            compose.activityRule.scenario.onActivity {
                statusBarVisible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars())
            }
            statusBarVisible == !fullScreen
        }
        choose("继续授权")
        compose.waitUntil { model.authStatus == "等待扫码" }
        compose.waitForIdle()
    }

    private fun assertVisibleAndScannableQr() {
        val image = compose.onNodeWithContentDescription("账号授权二维码", useUnmergedTree = true)
        val qr = image.fetchSemanticsNode()
        val title = compose.onNodeWithText("账号", useUnmergedTree = true).fetchSemanticsNode()
        val menu = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        compose.runOnIdle {
            val bounds = Rect(qr.positionInRoot, qr.size.toSize())
            val visible = qr.boundsInRoot
            val titleAncestors = generateSequence(title.layoutInfo) { it.parentInfo }.toSet()
            // The title and QR meet at the LCD's inner column, above the wheel container.
            val lcd = generateSequence(qr.layoutInfo) { it.parentInfo }.first { it in titleAncestors }.coordinates.boundsInRoot()
            val header = requireNotNull(title.layoutInfo.parentInfo).coordinates.boundsInRoot()
            val menuBounds = menu.boundsInRoot
            assertTrue("The QR must have a non-empty image", bounds.width > 0f && bounds.height > 0f)
            assertEquals("The QR must retain a square layout", bounds.width, bounds.height, 1f)
            assertEquals("The QR must be horizontally centered in the LCD", lcd.center.x, bounds.center.x, 1f)
            assertEquals("The QR left edge must not be clipped", bounds.left, visible.left, 1f)
            assertEquals("The QR top edge must not be clipped", bounds.top, visible.top, 1f)
            assertEquals("The QR right edge must not be clipped", bounds.right, visible.right, 1f)
            assertEquals("The QR bottom edge must not be clipped", bounds.bottom, visible.bottom, 1f)
            assertTrue("The complete QR must fit inside the LCD", bounds.left >= lcd.left - 1f && bounds.top >= lcd.top - 1f && bounds.right <= lcd.right + 1f && bounds.bottom <= lcd.bottom + 1f)
            assertTrue("The QR must start below the LCD title bar", bounds.top >= header.bottom - 1f)
            assertTrue("The menu must remain visible below the QR", menuBounds.height > 0f && bounds.bottom <= menuBounds.top + 1f)
            assertTrue("The menu must remain inside the LCD", menuBounds.bottom <= lcd.bottom + 1f)
        }
        val bitmap = image.captureToImage().asAndroidBitmap()
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
            assertEquals("The displayed QR must decode to the full authorization URL", source.qrUrl, decoded.text)
        } finally { bitmap.recycle() }
    }

    private fun choose(title: String) = compose.runOnIdle {
        val index = model.page().rows.indexOfFirst { it.title == title }
        assertTrue("Missing action $title", index >= 0)
        model.focus(index); model.key(WheelKey.CENTER)
    }

    private fun poll(index: Int): CompletableDeferred<PluginAuthSession> {
        compose.waitUntil(5_000) { source.polls.size > index }
        return source.polls[index]
    }

    private class ControlledAccounts : AccountSource {
        val account = PluginAccount("service-account", "Auth fixture", "verified-scope", "SignedIn")
        val polls = CopyOnWriteArrayList<CompletableDeferred<PluginAuthSession>>()
        val cancelled = CopyOnWriteArrayList<String>()
        var signedIn = false
        var accountState = "SignedIn"
        val qrUrl = "https://music.example.org/login?codekey=0123456789abcdef0123456789abcdef"
        fun session(state: String) = PluginAuthSession("auth-session", state, qrUrl, SystemClock.elapsedRealtime() + 60_000)
        override suspend fun accounts(plugin: String) = if (signedIn) listOf(account.copy(state = accountState)) else emptyList()
        override suspend fun beginAuth(plugin: String) = session("WaitingScan")
        override suspend fun pollAuth(plugin: String, session: String): PluginAuthSession {
            val result = CompletableDeferred<PluginAuthSession>()
            polls += result
            return result.await()
        }
        override suspend fun cancelAuth(plugin: String, session: String) { cancelled += session }
        override suspend fun signOut(plugin: String, account: String) { signedIn = false }
    }
}
