package app.easepod.data

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.ogg.OggExtractor
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

@androidx.annotation.OptIn(UnstableApi::class)
internal object EmbeddedLyrics {
    const val MAX_LYRICS_BYTES = 1024 * 1024
    private const val MAX_HEADER_BYTES = 8 * 1024 * 1024
    private val lyricKeys = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")
    private val id3TextLyricKeys = lyricKeys + setOf("USLT", "SYLT")

    fun read(stream: InputStream, fileName: String, checkActive: () -> Unit = {}): String? {
        val input = PushbackInputStream(LimitedInputStream(stream, checkActive), 10)
        val header = input.readNBytes(10)
        input.unread(header)
        if (header.size == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
            require(header.sliceArray(6..9).all { it.toInt() and 0x80 == 0 }) { "无效的 ID3 标签长度" }
            val length = header.sliceArray(6..9).fold(0) { value, byte -> (value shl 7) or byte.toInt() }
            require(length <= MAX_HEADER_BYTES - 10) { "内嵌标签超过 8 MiB 上限" }
            val tag = input.readNBytes(length + 10)
            require(tag.size == length + 10) { "内嵌标签不完整" }
            val metadata = Id3Decoder { version, a, b, c, d ->
                val id = if (version == 2) charArrayOf(a.toChar(), b.toChar(), c.toChar()).concatToString()
                else charArrayOf(a.toChar(), b.toChar(), c.toChar(), d.toChar()).concatToString()
                id in setOf("USLT", "ULT", "TXXX", "TXX", "SYLT", "SLT")
            }.decode(tag, tag.size)
            fromMetadata(metadata)?.let { return it }
        }
        val extractor = when (fileName.substringAfterLast('.', "").lowercase()) {
            "flac" -> FlacExtractor(FlacExtractor.FLAG_DISABLE_ID3_METADATA)
            "ogg" -> OggExtractor()
            else -> return null
        }
        var foundFormat = false
        var lyrics: String? = null
        val output = object : TrackOutput by DummyTrackOutput() {
            override fun format(format: Format) {
                foundFormat = true
                lyrics = fromMetadata(format.metadata)
            }
        }
        try {
            // Unknown input length avoids seeking to the end of a large Ogg file for duration.
            val extractorInput = DefaultExtractorInput({ target, offset, length -> input.read(target, offset, length) }, 0, C.LENGTH_UNSET.toLong())
            if (!extractor.sniff(extractorInput)) return null
            extractorInput.resetPeekPosition()
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int) = output
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            val position = PositionHolder()
            while (!foundFormat) {
                checkActive()
                if (extractor.read(extractorInput, position) != Extractor.RESULT_CONTINUE) break
            }
            return lyrics
        } finally { extractor.release() }
    }

    internal fun fromMetadata(metadata: Metadata?): String? {
        if (metadata == null) return null
        for (index in 0 until metadata.length()) {
            val value = when (val entry = metadata[index]) {
                is VorbisComment -> entry.value.takeIf { entry.key.uppercase() in lyricKeys }
                is TextInformationFrame -> entry.values.joinToString("\n").takeIf { entry.description?.uppercase() in id3TextLyricKeys }
                is BinaryFrame -> when (entry.id) {
                    "USLT", "ULT" -> unsynchronized(entry.data)
                    "SYLT", "SLT" -> synchronized(entry.data)
                    else -> null
                }
                else -> null
            }
            validText(value)?.let { return it }
        }
        return null
    }

    private fun unsynchronized(data: ByteArray): String? {
        if (data.size !in 5..MAX_LYRICS_BYTES) return null
        val encoding = data[0].toInt()
        val start = endOfText(data, 4, encoding) ?: return null
        return decode(data, start, data.size, encoding)?.trimEnd('\u0000')
    }

    private fun synchronized(data: ByteArray): String? {
        // MPEG-frame timestamps need decoder timing; accept the standard millisecond form.
        if (data.size !in 7..MAX_LYRICS_BYTES || data[4].toInt() != 2) return null
        val encoding = data[0].toInt()
        var cursor = endOfText(data, 6, encoding) ?: return null
        val lines = mutableListOf<Pair<Long, String>>()
        while (cursor < data.size && lines.size < 10_000) {
            val end = endOfText(data, cursor, encoding) ?: return null
            if (end + 4 > data.size) return null
            val text = decode(data, cursor, end - if (encoding == 1 || encoding == 2) 2 else 1, encoding) ?: return null
            val timestamp = (end until end + 4).fold(0L) { value, index -> (value shl 8) or (data[index].toLong() and 0xff) }
            lines += timestamp to text.replace('\n', ' ').replace('\r', ' ')
            cursor = end + 4
        }
        if (cursor != data.size) return null
        return lines.sortedBy { it.first }.joinToString("\n") { (time, text) ->
            val minutes = time / 60_000
            val seconds = (time / 1000 % 60).toString().padStart(2, '0')
            val millis = (time % 1000).toString().padStart(3, '0')
            "[$minutes:$seconds.$millis]$text"
        }
    }

    private fun endOfText(data: ByteArray, start: Int, encoding: Int): Int? {
        if (encoding !in 0..3) return null
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        var index = start
        while (index + width <= data.size) {
            if (data[index] == 0.toByte() && (width == 1 || data[index + 1] == 0.toByte())) return index + width
            index += width
        }
        return null
    }

    private fun decode(data: ByteArray, start: Int, end: Int, encoding: Int): String? {
        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        return runCatching { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, start, end - start)).toString().removePrefix("\uFEFF") }.getOrNull()
    }

    private fun validText(value: String?): String? = value?.takeIf {
        it.isNotBlank() && it.length <= MAX_LYRICS_BYTES && it.toByteArray(Charsets.UTF_8).size <= MAX_LYRICS_BYTES && '\u0000' !in it
    }

    private class LimitedInputStream(input: InputStream, private val checkActive: () -> Unit) : FilterInputStream(input) {
        private var remaining = MAX_HEADER_BYTES
        override fun read(): Int {
            checkActive()
            if (remaining == 0) return -1
            val result = `in`.read()
            if (result != -1) remaining--
            return result
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkActive()
            if (length == 0) return 0
            if (remaining == 0) return -1
            val count = `in`.read(buffer, offset, minOf(length, remaining))
            if (count > 0) remaining -= count
            return count
        }
    }
}
