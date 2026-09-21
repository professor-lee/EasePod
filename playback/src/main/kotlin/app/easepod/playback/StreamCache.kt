package app.easepod.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet

@UnstableApi
internal class StreamCache(context: Context, limitMb: Int) {
    private val evictor = AdjustableLruEvictor(limitMb.coerceIn(16, 4096) * 1024L * 1024L)
    val cache = SimpleCache(File(context.cacheDir, "media-streams"), evictor, StandaloneDatabaseProvider(context))

    fun setLimit(mb: Int) = synchronized(cache) {
        evictor.setLimit(cache, mb.coerceIn(16, 4096) * 1024L * 1024L)
    }

    fun clear() { cache.keys.toList().forEach(cache::removeResource) }
    fun removeAccount(sourceId: String, accountScope: String, legacyKeys: List<String> = emptyList()) {
        val prefix = streamAccountPrefix(sourceId, accountScope)
        (legacyKeys + cache.keys.filter { it.startsWith(prefix) }).forEach(cache::removeResource)
    }
    fun release() = cache.release()

    private class AdjustableLruEvictor(private var limit: Long) : CacheEvictor {
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position })
        private var bytes = 0L
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() = Unit
        // SimpleCache serializes these callbacks under its own monitor.
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) = trim(cache, length.coerceAtLeast(0L))
        override fun onSpanAdded(cache: Cache, span: CacheSpan) { spans.add(span); bytes += span.length; trim(cache, 0L) }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) bytes -= span.length }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) { onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan) }
        fun setLimit(cache: Cache, value: Long) { limit = value; trim(cache, 0L) }
        private fun trim(cache: Cache, incoming: Long) {
            while (bytes + incoming > limit && spans.isNotEmpty()) cache.removeSpan(spans.first())
        }
    }
}
