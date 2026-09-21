package app.easepod.data

internal object ScanRules {
    private val audioExtensions = setOf("mp3", "wav", "flac", "ogg")
    private val covers = listOf("png", "jpg", "jpeg", "gif")
    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in audioExtensions
    fun isLyrics(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("lrc", "txt")
    fun coverPriority(name: String): Int? {
        if (!name.substringBeforeLast('.', "").equals("cover", ignoreCase = true)) return null
        return covers.indexOf(name.substringAfterLast('.', "").lowercase()).takeIf { it >= 0 }
    }
    fun positiveTag(raw: String?): Int = raw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it in 1..99999 } ?: 0
    fun compareNatural(left: String, right: String): Int {
        var a = 0
        var b = 0
        while (a < left.length && b < right.length) {
            if (left[a] in '0'..'9' && right[b] in '0'..'9') {
                val aStart = a
                val bStart = b
                while (a < left.length && left[a] in '0'..'9') a++
                while (b < right.length && right[b] in '0'..'9') b++
                val x = left.substring(aStart, a).trimStart('0').ifEmpty { "0" }
                val y = right.substring(bStart, b).trimStart('0').ifEmpty { "0" }
                val comparison = x.length.compareTo(y.length).takeIf { it != 0 } ?: x.compareTo(y)
                if (comparison != 0) return comparison
            } else {
                val comparison = left[a].lowercaseChar().compareTo(right[b].lowercaseChar())
                if (comparison != 0) return comparison
                a++; b++
            }
        }
        return (left.length - a).compareTo(right.length - b).takeIf { it != 0 } ?: left.compareTo(right)
    }
    val trackComparator = Comparator<TrackRow> { a, b ->
        compareValues(a.discNumber.takeIf { it > 0 } ?: Int.MAX_VALUE, b.discNumber.takeIf { it > 0 } ?: Int.MAX_VALUE)
            .takeIf { it != 0 }
            ?: compareValues(a.trackNumber.takeIf { it > 0 } ?: Int.MAX_VALUE, b.trackNumber.takeIf { it > 0 } ?: Int.MAX_VALUE).takeIf { it != 0 }
            ?: compareNatural(a.fileName, b.fileName).takeIf { it != 0 }
            ?: compareValues(a.documentId, b.documentId)
    }
}
