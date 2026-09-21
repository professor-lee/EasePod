package app.easepod.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.easepod.MainActivity
import app.easepod.contract.CloudPlaylist
import app.easepod.contract.LibraryMutation
import app.easepod.contract.MutationResult
import app.easepod.core.AppSettings
import app.easepod.core.Track
import app.easepod.core.WheelKey
import app.easepod.plugins.CloudLibraryAccess
import app.easepod.plugins.CloudMutationReceipt
import app.easepod.plugins.CloudPlaylistPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CloudWriteDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AppModel
    private lateinit var originalSettings: AppSettings
    private lateinit var originalCloud: CloudLibraryAccess
    private var originalLock: (() -> Boolean)? = null
    private var locked = false
    private val cloud = ControlledCloud()
    private val track = Track("fixture-track", "Cloud track", "Artist", sourceId = "fixture-source", accountScope = "host-scope", remoteId = "remote-track")

    @Before fun prepare() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppModel::class.java] }
        runBlocking {
            model.app.awaitStartupResources()
            originalSettings = model.app.settings.persistedSettings.first()
            model.app.settings.update { it.copy(touchGuard = true, safeMode = false, reducedMotion = true, clickSound = false, haptics = false) }
        }
        compose.waitUntil(10_000) { model.library.ready && model.settings.touchGuard && !model.settings.safeMode }
        compose.runOnIdle {
            originalCloud = model.cloudLibrary; originalLock = model.lockQuery
            model.cloudLibrary = cloud; model.lockQuery = { locked }; model.refreshLock(false); model.home()
        }
    }

    @After fun restore() {
        compose.runOnIdle {
            model.home(); model.cloudLibrary = originalCloud; model.lockQuery = originalLock
            model.refreshLock(originalLock?.invoke() ?: false)
        }
        runBlocking { model.app.settings.update { originalSettings } }
    }

    @Test fun guardedCloudFavoriteRequiresWheelSelectionAndExplicitConfirmation() {
        compose.runOnIdle { model.cloudFavoriteMenu("fixture", track) }
        compose.waitUntil { model.dialog?.message == "未收藏" }
        compose.onNode(hasText("收藏歌曲") and hasClickAction()).performTouchInput { click() }
        assertTrue(cloud.submissions.isEmpty())
        compose.runOnIdle { assertEquals("Cloud track", model.dialog?.title) }
        compose.onNodeWithContentDescription("中心，确认").performTouchInput { click() }
        compose.runOnIdle { assertEquals("收藏歌曲？", model.dialog?.title); assertTrue(cloud.submissions.isEmpty()); model.rotate(1) }
        compose.onNodeWithContentDescription("中心，确认").performTouchInput { click() }
        compose.waitUntil { cloud.submissions.size == 1 }
        compose.runOnIdle {
            val request = cloud.submissions.single()
            assertEquals("host-scope", request.account); assertEquals("remote-track", request.remoteId)
            assertTrue(request.mutation.desiredFavorite); assertEquals("FAVORITE", request.mutation.action)
        }
    }

    @Test fun lockingInvalidatesAnAlreadyDisplayedCloudConfirmation() {
        var staleConfirm: (() -> Unit)? = null
        compose.runOnIdle {
            model.confirmCloudMutation("fixture", "host-scope", "remote-track", "Cloud track",
                LibraryMutation("mutation-lock", "FAVORITE", desiredFavorite = true), "收藏歌曲？")
            staleConfirm = model.dialog!!.rows.last().action
            locked = true; model.refreshLock(true)
            staleConfirm!!.invoke()
            assertTrue(cloud.submissions.isEmpty())
            model.confirmCloudMutation("fixture", "host-scope", "remote-track", "Cloud track",
                LibraryMutation("mutation-lock-2", "FAVORITE", desiredFavorite = true), "收藏歌曲？")
            assertEquals("解锁后继续", model.dialog?.title)
            assertTrue(cloud.submissions.isEmpty())
        }
    }

    @Test fun unknownOutcomeOffersReadbackAndNeverRepeatsTheMutation() {
        cloud.nextStatus = "UNKNOWN"
        compose.runOnIdle {
            model.confirmCloudMutation("fixture", "host-scope", "remote-track", "Cloud track",
                LibraryMutation("mutation-unknown", "FAVORITE", desiredFavorite = true), "收藏歌曲？")
            model.rotate(1); model.key(WheelKey.CENTER)
        }
        compose.waitUntil { model.dialog?.title == "待确认操作" }
        compose.runOnIdle {
            assertEquals(1, cloud.submissions.size)
            model.dialogActivate(0)
            assertEquals("刷新结果", model.dialog?.rows?.first()?.title)
            model.dialogActivate(0)
        }
        compose.waitUntil { model.dialog?.message == "已核对，云端已更新" }
        compose.runOnIdle { assertEquals(1, cloud.submissions.size); assertEquals(1, cloud.refreshes); assertTrue(cloud.pending.value.isEmpty()) }
    }

    private data class Submission(val account: String, val remoteId: String?, val mutation: LibraryMutation)
    private class ControlledCloud : CloudLibraryAccess {
        override val pending = MutableStateFlow<List<CloudMutationReceipt>>(emptyList())
        val submissions = mutableListOf<Submission>()
        var nextStatus = "APPLIED"
        var refreshes = 0
        override suspend fun favorite(pluginId: String, accountScope: String, trackId: String) = false
        override suspend fun playlists(pluginId: String, accountScope: String, cursor: String?) = CloudPlaylistPage(emptyList(), null)
        override suspend fun playlist(pluginId: String, accountScope: String, playlistId: String) = CloudPlaylist(playlistId, "Playlist", "r1")
        override suspend fun submit(pluginId: String, accountScope: String, remoteId: String?, title: String, mutation: LibraryMutation, authorized: () -> Boolean): MutationResult {
            check(authorized()); submissions += Submission(accountScope, remoteId, mutation)
            if (nextStatus == "UNKNOWN") pending.value += CloudMutationReceipt(mutation.mutationId, pluginId, accountScope, "SetFavorite", remoteId, mutation.action, title, mutation.desiredFavorite)
            return MutationResult(mutation.mutationId, nextStatus, remoteId = remoteId)
        }
        override suspend fun refresh(receipt: CloudMutationReceipt): MutationResult {
            refreshes++; pending.value = emptyList(); return MutationResult(receipt.id, "APPLIED")
        }
    }
}
