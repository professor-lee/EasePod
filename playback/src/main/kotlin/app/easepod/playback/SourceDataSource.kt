package app.easepod.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import app.easepod.core.LOCAL_SOURCE
import app.easepod.core.Track
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
internal class SourceDataSource(
    private val context: Context,
    private val streams: StreamResolver,
    private val cache: StreamCache,
    private val registry: ConcurrentHashMap<String, Track>,
    private val allowed: (Track) -> Boolean,
    private val offline: suspend (Track) -> PlayableSource?,
    private val onSourceResolved: (String, PlayableSource) -> Unit,
    private val upstreamFactory: (PlayableSource) -> DataSource.Factory = { source ->
        if (source.uri.startsWith("https://")) DefaultDataSource.Factory(context, MediaHttpPolicy.factory(source))
        else DefaultDataSource.Factory(context)
    },
) : DataSource {
    private var delegate: DataSource? = null
    private var track: Track? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun open(dataSpec: DataSpec): Long {
        val entryId = dataSpec.uri.lastPathSegment ?: throw SourceUnavailableException("队列项目已移除")
        val selected = registry[entryId] ?: throw SourceUnavailableException("队列项目已移除")
        track = selected
        checkAllowed(selected)
        var source = if (selected.sourceId == LOCAL_SOURCE) {
            val uri = selected.contentUri ?: throw SourceUnavailableException("本地文件不可用，请重新扫描")
            if (Uri.parse(uri).scheme != "content") throw SourceUnavailableException("本地文件没有有效目录授权")
            PlayableSource(uri)
        } else runBlocking {
            withTimeout(8_000L) {
                offline(selected) ?: validatedSource(streams.resolve(selected))
            }
        }
        checkAllowed(selected)
        for (attempt in 0..1) {
            val key = streamCacheKey(selected, source)
            val upstream = upstreamFactory(source)
            val factory: DataSource.Factory = if (key != null) CacheDataSource.Factory().setCache(cache.cache)
                .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setCacheKeyFactory { key } else upstream
            val opened = factory.createDataSource()
            delegate = opened
            listeners.forEach(opened::addTransferListener)
            try {
                return opened.open(dataSpec.buildUpon().setUri(source.uri).setKey(key).build()).also {
                    checkAllowed(selected)
                    onSourceResolved(entryId, source)
                }
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (attempt != 0 || selected.sourceId == LOCAL_SOURCE || error.responseCode !in setOf(401, 403)) throw error
                runCatching { opened.close() }
                delegate = null
                checkAllowed(selected)
                source = runBlocking { withTimeout(8_000L) { validatedSource(streams.resolve(selected)) } }
                checkAllowed(selected)
            }
        }
        throw SourceUnavailableException("媒体授权刷新失败")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        track?.let(::checkAllowed)
        return delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    private fun checkAllowed(track: Track) {
        if (!allowed(track)) throw SourceUnavailableException("来源已停用或文件授权失效")
    }

    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; delegate?.addTransferListener(transferListener) }
    override fun close() { delegate?.close(); delegate = null; track = null }
}
