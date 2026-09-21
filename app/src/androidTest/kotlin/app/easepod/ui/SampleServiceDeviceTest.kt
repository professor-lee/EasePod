package app.easepod.ui

import android.media.AudioManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.easepod.EasePodApplication
import app.easepod.MainActivity
import app.easepod.core.PlaybackSnapshot
import app.easepod.core.RepeatMode
import app.easepod.plugins.PluginException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SampleServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as EasePodApplication

    @Test fun approvedPublicServiceStreamsAndDisablingNeverRestartsPlayback() = runBlocking {
        val installed = runCatching { context.packageManager.getPackageInfo(SAMPLE_PACKAGE, 0) }.isSuccess
        assumeTrue("Install the known SoundHelix sample APK before this integration test", installed)
        val deadline = SystemClock.elapsedRealtime() + 55_000L
        val originalSettings = withTimeout(remaining(deadline)) { app.settings.persistedSettings.first() }
        val audio = context.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var originalEnabled: Boolean? = null
        var originalPlayback: PlaybackSnapshot? = null
        var mutated = false
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                withTimeout(remaining(deadline)) {
                    app.awaitStartupResources()
                    app.plugins.refresh()
                    val sample = app.plugins.plugins.value.singleOrNull { it.id == SAMPLE_PACKAGE && it.packageName == SAMPLE_PACKAGE }
                    assumeTrue("The installed sample APK must expose the EasePod plugin contract", sample != null)
                    originalEnabled = sample!!.enabled
                    originalPlayback = app.player.playback.value
                    mutated = true
                    app.settings.update { it.copy(safeMode = false, networkQuality = "standard") }
                    app.plugins.setSafeMode(false)
                    main { app.player.pause(); app.player.volume(0f); app.player.setSafeMode(false); app.player.repeat(RepeatMode.OFF); app.player.shuffle(false) }
                    app.plugins.approve(sample.id, sample.signature)
                    app.plugins.setEnabled(sample.id, true)
                    val catalog = try {
                        app.plugins.browse(sample.id)
                    } catch (error: PluginException) {
                        assumeTrue("Approve the EasePod host signature in the sample plugin first", error.code != "HostNotApproved")
                        throw error
                    }
                    assertEquals(3, catalog.tracks.size)
                    assertEquals(setOf("soundhelix-1", "soundhelix-2", "soundhelix-3"), catalog.tracks.map { it.remoteId }.toSet())
                    val searched = app.plugins.search(sample.id, "SoundHelix")
                    assertEquals(catalog.tracks.map { it.id }.toSet(), searched.tracks.map { it.id }.toSet())
                    val selected = catalog.tracks.first { it.remoteId == "soundhelix-1" }
                    val resolved = app.plugins.resolve(selected)
                    assertEquals("NO_STORE", resolved.cachePolicy)
                    assertEquals("www.soundhelix.com", java.net.URI(resolved.uri).host)
                    assertTrue(resolved.uri.startsWith("https://"))
                    main { app.player.play(catalog.tracks, catalog.tracks.indexOf(selected)) }
                    await(deadline) {
                        val state = app.player.playback.value
                        if (state.current?.id == selected.id) assertNull("HTTPS playback failed: ${state.error}", state.error)
                        state.current?.id == selected.id && state.playing && state.positionMs >= 800L && state.canSeek
                    }
                    val before = app.player.playback.value.positionMs
                    val seekTo = before + 5_000L
                    main { app.player.seek(seekTo) }
                    await(deadline) { app.player.playback.value.positionMs >= seekTo && app.player.playback.value.playing }
                    assertTrue(app.player.playback.value.positionMs > before)
                    app.plugins.setEnabled(sample.id, false)
                    await(deadline) { !app.player.playback.value.playing && app.player.playback.value.current?.available == false }
                    assertEquals(3, app.player.playback.value.queue.size)
                    app.plugins.setEnabled(sample.id, true)
                    await(deadline) { app.plugins.isAccountActive(selected.sourceId, selected.accountScope) }
                    val stoppedAt = app.player.playback.value.positionMs
                    delay(700L)
                    assertFalse("Re-enabling a service must not resume audio", app.player.playback.value.playing)
                    assertEquals(stoppedAt, app.player.playback.value.positionMs)
                }
            } finally {
                if (mutated) {
                    try {
                        withTimeout(5_000L) {
                            main {
                                app.player.pause()
                                app.player.clear()
                                originalPlayback?.let { previous -> app.player.repeat(previous.repeat); app.player.shuffle(previous.shuffle) }
                            }
                            originalEnabled?.let { app.plugins.setEnabled(SAMPLE_PACKAGE, it) }
                            app.settings.update { originalSettings }
                            app.plugins.setSafeMode(originalSettings.safeMode)
                            while (app.player.playback.value.queue.isNotEmpty() ||
                                app.player.playback.value.repeat != originalPlayback?.repeat ||
                                app.player.playback.value.shuffle != originalPlayback?.shuffle) delay(25L)
                        }
                    } finally {
                        main {
                            app.player.setSafeMode(originalSettings.safeMode)
                            app.player.pause()
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                        }
                    }
                }
                activity.moveToState(Lifecycle.State.CREATED)
            }
        }
    }

    private suspend fun await(deadline: Long, predicate: () -> Boolean) {
        while (!predicate()) {
            assertTrue("Timed out waiting for public HTTPS playback: ${app.player.playback.value}", SystemClock.elapsedRealtime() < deadline)
            delay(25L)
        }
    }

    private fun remaining(deadline: Long) = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    companion object { private const val SAMPLE_PACKAGE = "app.easepod.sampleplugin" }
}
