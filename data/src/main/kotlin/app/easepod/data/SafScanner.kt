package app.easepod.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import app.easepod.core.LOCAL_SOURCE
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ScanDocument(val id: String, val name: String, val mime: String, val uri: Uri) {
    val directory get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}
internal data class ScanResult(val tracks: List<TrackRow>, val albums: List<AlbumRow>, val members: List<AlbumMemberRow>, val skipped: Int, val issues: List<String>)

internal class SafScanner(private val context: Context) {
    private val resolver = context.contentResolver
    private val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)

    fun readableRoot(treeUri: String): ScanDocument {
        val tree = Uri.parse(treeUri)
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "请选择系统提供的音乐文件夹" }
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission }) throw SecurityException("未取得可持久读取权限，请重新选择文件夹")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "文件夹不可访问，请重新选择" }
            ScanDocument(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), uri).also {
                require(it.directory && it.id == DocumentsContract.getTreeDocumentId(tree)) { "选择的文档不是原文件夹" }
            }
        } ?: throw IOException("无法读取文件夹")
    }

    private suspend fun children(tree: Uri, document: ScanDocument): List<ScanDocument> {
        currentCoroutineContext().ensureActive()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, document.id)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            check(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "文件夹仍在加载，请稍后重新扫描" }
            check(cursor.extras.getString(DocumentsContract.EXTRA_ERROR) == null) { "文件夹列表读取失败，请重试" }
            buildList {
                val seen = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getString(0) ?: throw IOException("文档缺少标识，扫描未完成")
                    check(id.isNotEmpty() && id.length <= 4096) { "文档标识不合法" }
                    check(seen.add(id) && id != document.id) { "文件夹返回了重复或循环文档" }
                    add(ScanDocument(id, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), DocumentsContract.buildDocumentUriUsingTree(tree, id)))
                    check(size <= 100_000) { "单个文件夹条目超过限制" }
                }
                check(!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "文件夹列表不完整，请重试" }
                check(cursor.extras.getString(DocumentsContract.EXTRA_ERROR) == null) { "文件夹列表读取失败，请重试" }
            }
        } ?: throw IOException("无法完整列出文件夹 ${document.name.take(80)}")
    }

    suspend fun scan(root: RootRow, jobId: String, existing: List<TrackRow>, oldAlbums: List<AlbumRow>, onProgress: suspend (Int, Int, Int, String) -> Unit): ScanResult {
        val tree = Uri.parse(root.uri)
        val base = readableRoot(root.uri)
        val previousTracks = existing.filter { it.rootId == root.id }.associateBy { it.documentId }
        val previousAlbums = oldAlbums.filter { it.rootId == root.id }.associateBy { it.documentId }
        val tracks = mutableListOf<TrackRow>()
        val albums = mutableListOf<AlbumRow>()
        val members = mutableListOf<AlbumMemberRow>()
        val issues = mutableListOf<String>()
        var skipped = 0
        val seenDocuments = hashSetOf<String>()

        fun issue(message: String) { skipped++; if (issues.size < 100) issues += message.take(240) }
        suspend fun audio(document: ScanDocument): TrackRow? {
            currentCoroutineContext().ensureActive()
            check(seenDocuments.add(document.id)) { "同一文档出现在多个目录，扫描未完成" }
            return try {
                readTrack(document, root, jobId, previousTracks[document.id]?.id ?: UUID.randomUUID().toString())
            } catch (error: SecurityException) { throw error }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                issue("无法解析音频：${document.name}"); null
            }
        }
        val rootChildren = children(tree, base)
        for (document in rootChildren.sortedWith { a, b -> ScanRules.compareNatural(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }) {
            currentCoroutineContext().ensureActive()
            if (document.directory) {
                val entries = children(tree, document)
                val albumTracks = mutableListOf<TrackRow>()
                for (child in entries) {
                    if (child.directory) issue("未递归子目录：${child.name}")
                    else if (ScanRules.isAudio(child.name)) audio(child)?.let { albumTracks += it }
                    else if (ScanRules.coverPriority(child.name) == null && !ScanRules.isLyrics(child.name)) issue("不支持的文件：${child.name}")
                    onProgress(tracks.size + albumTracks.size, albums.size, skipped, document.name)
                }
                if (albumTracks.isNotEmpty()) {
                    val sorted = albumTracks.sortedWith(ScanRules.trackComparator)
                    val cover = entries.filter { ScanRules.coverPriority(it.name) != null && !it.directory }
                        .sortedWith(compareBy<ScanDocument> { ScanRules.coverPriority(it.name) }.thenBy { it.name }.thenBy { it.id })
                        .firstNotNullOfOrNull { cover ->
                            try { readCover(cover.uri) ?: run { issue("无效封面：${cover.name}"); null } }
                            catch (error: SecurityException) { throw error }
                            catch (_: Exception) { issue("无法读取封面：${cover.name}"); null }
                        } ?: sorted.firstNotNullOfOrNull { it.artworkUri }
                    val albumId = previousAlbums[document.id]?.id ?: UUID.randomUUID().toString()
                    val artists = sorted.map { it.artist }.distinct()
                    albums += AlbumRow(albumId, root.id, document.id, jobId, document.name.take(512), artists.singleOrNull() ?: "群星", cover)
                    sorted.forEachIndexed { position, track ->
                        tracks += track.copy(artworkUri = cover)
                        members += AlbumMemberRow(track.id, albumId, position)
                    }
                }
            } else if (ScanRules.isAudio(document.name)) audio(document)?.let { tracks += it }
            else if (!ScanRules.isLyrics(document.name)) issue("不支持的根目录文件：${document.name}")
            check(tracks.size <= 100_000) { "音乐库超过 100000 首上限" }
            onProgress(tracks.size, albums.size, skipped, document.name)
        }
        readableRoot(root.uri)
        return ScanResult(tracks, albums, members, skipped, issues)
    }

    private fun readTrack(document: ScanDocument, root: RootRow, scanId: String, trackId: String): TrackRow {
        require(!document.mime.startsWith("image/") && !document.mime.startsWith("text/"))
        val extractor = MediaExtractor()
        var duration: Long? = null
        try {
            extractor.setDataSource(context, document.uri, null)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            require(formats.isNotEmpty())
            val supported = formats.firstOrNull { format ->
                format.getString(MediaFormat.KEY_MIME) == "audio/raw" || MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
            } ?: throw IOException("不支持的音频编码")
            duration = if (supported.containsKey(MediaFormat.KEY_DURATION)) supported.getLong(MediaFormat.KEY_DURATION).div(1000).takeIf { it >= 0 } else null
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, document.uri)
            fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }?.take(512)
            val artwork = retriever.embeddedPicture?.takeIf { it.size <= MAX_ART_BYTES }?.let(::cacheArtwork)
            val remoteId = "saf/${root.id}/${Base64.getUrlEncoder().withoutPadding().encodeToString(document.id.toByteArray(Charsets.UTF_8))}"
            return TrackRow(trackId, root.id, document.id, scanId,
                tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: document.name.substringBeforeLast('.').take(512),
                tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "未知艺人", tag(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "未分类",
                tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it >= 0 } ?: duration,
                artwork, document.uri.toString(), true, LOCAL_SOURCE, "local", remoteId,
                ScanRules.positiveTag(tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)), ScanRules.positiveTag(tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)), document.name)
        } finally { retriever.release() }
    }

    private fun readCover(uri: Uri): String? = resolver.openInputStream(uri)?.use { stream ->
        val bytes = stream.readNBytes(MAX_ART_BYTES + 1)
        if (bytes.size > MAX_ART_BYTES) null else cacheArtwork(bytes)
    }

    suspend fun lyrics(root: RootRow, track: TrackRow, directoryId: String): String? {
        readableRoot(root.uri)
        val tree = Uri.parse(root.uri)
        val directory = ScanDocument(directoryId, "", DocumentsContract.Document.MIME_TYPE_DIR, DocumentsContract.buildDocumentUriUsingTree(tree, directoryId))
        val stem = track.fileName.substringBeforeLast('.', track.fileName)
        val candidates = children(tree, directory).filter { document ->
            !document.directory && document.name.substringBeforeLast('.', "").equals(stem, ignoreCase = true) &&
                ScanRules.isLyrics(document.name)
        }.sortedWith(compareBy<ScanDocument> { if (it.name.endsWith(".lrc", true)) 0 else 1 }.thenBy { it.name }.thenBy { it.id })
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val text = resolver.openInputStream(candidate.uri)?.use { input ->
                val bytes = input.readNBytes(EmbeddedLyrics.MAX_LYRICS_BYTES + 1)
                require(bytes.size <= EmbeddedLyrics.MAX_LYRICS_BYTES) { "歌词文件超过 1 MiB 上限" }
                val charset = when {
                    bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                    bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                    else -> Charsets.UTF_8
                }
                bytes.toString(charset).removePrefix("\uFEFF").takeIf { it.isNotBlank() }
            }
            currentCoroutineContext().ensureActive()
            if (text != null) {
                readableRoot(root.uri)
                return text
            }
        }
        val context = currentCoroutineContext()
        val trackUri = DocumentsContract.buildDocumentUriUsingTree(tree, requireNotNull(track.documentId))
        val embedded = resolver.openInputStream(trackUri)?.use { input ->
            EmbeddedLyrics.read(input, track.fileName) { context.ensureActive() }
        }
        context.ensureActive()
        readableRoot(root.uri)
        return embedded
    }

    private fun cacheArtwork(bytes: ByteArray): String? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth.toLong() * options.outHeight > 32_000_000) return null
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val directory = File(context.filesDir, "artwork").apply { mkdirs() }
            val file = File(directory, "$digest.png")
            if (!file.exists()) {
                val temporary = File.createTempFile("cover-", ".tmp", directory)
                try {
                    temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    check(temporary.renameTo(file))
                } finally { temporary.delete() }
            }
            Uri.fromFile(file).toString()
        } finally { bitmap.recycle() }
    }

    companion object { const val MAX_ART_BYTES = 8 * 1024 * 1024 }
}
