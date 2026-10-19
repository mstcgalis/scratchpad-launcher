package app.olauncher.helper

import java.text.BreakIterator

/** Categories are just the emoji an app's (renamed) label starts with: "🎵 Spotify" is in 🎵. */
object AppCategories {
    fun of(label: String): String? {
        if (label.isEmpty()) return null
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(label) }
        val first = label.substring(0, graphemes.next())
        return first.takeIf { Character.getType(it.codePointAt(0)) == Character.OTHER_SYMBOL.toInt() }
    }

    /** Distinct categories in first-seen order. */
    fun all(labels: List<String>): List<String> = labels.mapNotNull(::of).distinct()
}
