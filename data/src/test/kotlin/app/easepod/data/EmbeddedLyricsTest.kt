package app.easepod.data

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.flac.VorbisComment
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EmbeddedLyricsTest {
    @Test fun readsRealMp3FlacAndOggWithoutDecodingTheirAudio() {
        val formats = listOf("mp3", "flac", "ogg")
        val lyrics = formats.associateWith { extension ->
            requireNotNull(javaClass.getResourceAsStream("/lyrics/embedded.$extension")).use { stream ->
                EmbeddedLyrics.read(stream, "track.$extension")
            }
        }
        assertEquals(formats.associateWith { "[00:01.00]Embedded fixture" }, lyrics)
    }

    @Test fun id3UnsynchronizedLyricsHonorUtf16DescriptionAndByteOrder() {
        val description = "description".toByteArray(Charsets.UTF_16)
        val lyrics = "[00:01.00]中文歌词".toByteArray(Charsets.UTF_16)
        val frame = byteArrayOf(1) + "zho".toByteArray() + description + byteArrayOf(0, 0) + lyrics
        assertEquals("[00:01.00]中文歌词", EmbeddedLyrics.read(LyricsFixtures.id3("USLT", frame).inputStream(), "music.mp3"))
    }

    @Test fun synchronizedId3LyricsUseMillisecondsAndRejectFrameTimestamps() {
        val body = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(2, 1, 0) +
            "Second\u0000".toByteArray() + ByteBuffer.allocate(4).putInt(2300).array() +
            "First\u0000".toByteArray() + ByteBuffer.allocate(4).putInt(1000).array()
        assertEquals("[0:01.000]First\n[0:02.300]Second", EmbeddedLyrics.read(LyricsFixtures.id3("SYLT", body).inputStream(), "music.mp3"))
        body[4] = 1
        assertNull(EmbeddedLyrics.read(LyricsFixtures.id3("SYLT", body).inputStream(), "music.mp3"))
    }

    @Test fun vorbisReadsKnownKeysAndIgnoresOrdinaryCommentsAndEmptyLyrics() {
        val metadata = Metadata(VorbisComment("COMMENT", "Not lyrics"), VorbisComment("LYRICS", " "), VorbisComment("unsyncedlyrics", "Actual lyrics"))
        assertEquals("Actual lyrics", EmbeddedLyrics.fromMetadata(metadata))
        assertNull(EmbeddedLyrics.fromMetadata(Metadata(VorbisComment("LYRICS", "x".repeat(EmbeddedLyrics.MAX_LYRICS_BYTES + 1)))))
    }

    @Test fun id3StopsAfterItsTagAndRejectsMalformedOrExcessiveLengths() {
        val bytes = LyricsFixtures.id3("USLT", byteArrayOf(3) + "eng\u0000Lyrics".toByteArray())
        val stream = ByteArrayInputStream(bytes + ByteArray(100_000))
        assertEquals("Lyrics", EmbeddedLyrics.read(stream, "music.mp3"))
        assertEquals(100_000, stream.available())
        assertNotNull(runCatching { EmbeddedLyrics.read(byteArrayOf(73, 68, 51, 3, 0, 0, 127, 127, 127, 127).inputStream(), "music.mp3") }.exceptionOrNull())
        assertNotNull(runCatching { EmbeddedLyrics.read(bytes.copyOf(bytes.size - 1).inputStream(), "music.mp3") }.exceptionOrNull())
    }

    @Test fun cancellationInterruptsMetadataReads() {
        assertTrue(runCatching {
            EmbeddedLyrics.read(ByteArray(20).inputStream(), "music.flac") { throw CancellationException("Changed track") }
        }.exceptionOrNull() is CancellationException)
    }
}

internal object LyricsFixtures {
    fun id3(frameId: String, body: ByteArray): ByteArray {
        val frame = frameId.toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() + byteArrayOf(0, 0) + body
        val length = byteArrayOf((frame.size shr 21 and 127).toByte(), (frame.size shr 14 and 127).toByte(),
            (frame.size shr 7 and 127).toByte(), (frame.size and 127).toByte())
        return byteArrayOf(73, 68, 51, 3, 0, 0) + length + frame
    }
}
