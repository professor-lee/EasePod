package app.easepod.playback

import android.content.ContextWrapper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.easepod.core.Track
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SourceCacheDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "source-cache-${UUID.randomUUID()}")
    private val isolated = object : ContextWrapper(context) { override fun getCacheDir(): File = directory.apply { mkdirs() } }
    private lateinit var cache: StreamCache
    private var bytes = ByteArray(8192) { 1 }
    private var upstreamOpens = 0
    private var source = PlayableSource("https://media.example.com/a?signature=one", allowStreamCache = true, quality = "standard", revision = "r1")
    private val track = Track("song", "Song", sourceId = "service", accountScope = "first-account", remoteId = "remote")

    @Before fun createCache() { cache = StreamCache(isolated, 16) }
    @After fun releaseCache() { cache.release(); directory.deleteRecursively() }

    @Test fun signedUrlRefreshReusesBytesButQualityAndRevisionNeverReusePreviousEncoding() {
        assertArrayEquals(bytes, read(track))
        assertEquals(1, upstreamOpens)
        source = source.copy(uri = "https://media.example.com/a?signature=two")
        assertArrayEquals(bytes, read(track))
        assertEquals(1, upstreamOpens)
        bytes = ByteArray(8192) { 2 }
        source = source.copy(quality = "lossless")
        assertArrayEquals(bytes, read(track))
        assertEquals(2, upstreamOpens)
        bytes = ByteArray(8192) { 3 }
        source = source.copy(revision = "r2")
        assertArrayEquals(bytes, read(track))
        assertEquals(3, upstreamOpens)
    }

    @Test fun unknownRevisionAndNoStoreDoNotPersistAudio() {
        source = source.copy(revision = null)
        assertArrayEquals(bytes, read(track))
        bytes = ByteArray(8192) { 4 }
        assertArrayEquals(bytes, read(track))
        assertEquals(2, upstreamOpens)
        source = source.copy(revision = "r1", allowStreamCache = false)
        assertArrayEquals(bytes, read(track))
        assertTrue(cache.cache.keys.isEmpty())
    }

    @Test fun accountRemovalPurgesEveryQualityAndRevisionWithoutTouchingOtherAccounts() {
        read(track)
        source = source.copy(quality = "lossless", revision = "r2")
        read(track)
        val other = track.copy(accountScope = "second-account")
        read(other)
        assertEquals(3, cache.cache.keys.size)
        cache.removeAccount(track.sourceId, track.accountScope)
        assertEquals(setOf(streamCacheKey(other, source)), cache.cache.keys)
        val opens = upstreamOpens
        read(other)
        assertEquals(opens, upstreamOpens)
        read(track)
        assertEquals(opens + 1, upstreamOpens)
    }

    private fun read(selected: Track): ByteArray {
        val registry = ConcurrentHashMap<String, Track>().apply { put("entry", selected) }
        val reader = SourceDataSource(isolated, StreamResolver { source }, cache, registry, { true }, { null }, { _, _ -> },
            upstreamFactory = { DataSource.Factory {
                val delegate = ByteArrayDataSource(bytes)
                object : DataSource by delegate {
                    override fun open(dataSpec: DataSpec): Long { upstreamOpens++; return delegate.open(dataSpec) }
                }
            } })
        return try {
            reader.open(DataSpec.Builder().setUri("easepod://queue/entry").build())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally { reader.close() }
    }
}
