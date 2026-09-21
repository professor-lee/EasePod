package app.easepod.playback

import app.easepod.core.QueueEntry
import kotlin.random.Random

/** The displayed queue is the actual playback order. Only the unplayed suffix is shuffled. */
internal object QueueOrder {
    fun shuffled(entries: List<QueueEntry>, currentId: String?, random: Random = Random.Default): List<QueueEntry> {
        val current = entries.indexOfFirst { it.id == currentId }
        if (current < 0) return entries.shuffled(random)
        return entries.take(current + 1) + entries.drop(current + 1).shuffled(random)
    }

    fun original(entries: List<QueueEntry>, originalIds: List<String>): List<QueueEntry> {
        val positions = originalIds.withIndex().associate { it.value to it.index }
        return entries.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
    }

    fun moved(entries: List<QueueEntry>, id: String, delta: Int): List<QueueEntry> {
        val from = entries.indexOfFirst { it.id == id }
        if (from < 0) return entries
        val to = (from + delta).coerceIn(0, entries.lastIndex)
        return entries.toMutableList().apply { add(to, removeAt(from)) }
    }
}
