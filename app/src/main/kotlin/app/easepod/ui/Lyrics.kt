package app.easepod.ui

import app.easepod.plugins.LyricLine

private val lyricTimestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
private val lyricOffset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

internal fun parseLyrics(text: String): List<LyricLine> {
    val offset = lyricOffset.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val timed = mutableListOf<LyricLine>()
    val plain = mutableListOf<LyricLine>()
    text.lineSequence().take(4000).forEach { line ->
        val matches = lyricTimestamp.findAll(line).toList()
        if (matches.isNotEmpty()) {
            val value = line.substring(matches.last().range.last + 1).trim()
            matches.forEach { match ->
                val seconds = match.groupValues[2].toInt()
                if (seconds < 60) {
                    val fraction = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0
                    val time = match.groupValues[1].toLong() * 60000 + seconds * 1000 + fraction + offset
                    timed.add(LyricLine(time.coerceAtLeast(0), value))
                }
            }
        } else if (line.isNotBlank() && !line.trimStart().startsWith("[")) plain.add(LyricLine(null, line.trim()))
    }
    return (if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else plain).take(2000)
}
