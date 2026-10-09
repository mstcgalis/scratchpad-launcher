package app.olauncher.helper

/** A character range in the source text. [start] inclusive, [end] exclusive. */
data class TextRange(val start: Int, val end: Int)

enum class InlineStyle { BOLD, ITALIC, UNDERLINE, STRIKE, HIGHLIGHT, CODE }

sealed class MarkdownMatch {
    data class Header(val level: Int, val markerRange: TextRange, val contentRange: TextRange) : MarkdownMatch()
    data class Inline(val style: InlineStyle, val openMarker: TextRange, val content: TextRange, val closeMarker: TextRange) : MarkdownMatch()
    /** `[text](url)`, or a bare URL (empty markers). */
    data class Link(val url: String, val openMarker: TextRange, val content: TextRange, val closeMarker: TextRange) : MarkdownMatch()
    /** The backslash of a `\*`-style escape. */
    data class Escape(val markerRange: TextRange) : MarkdownMatch()
    data class Bullet(val markerRange: TextRange) : MarkdownMatch()
    data class Checkbox(val checked: Boolean, val markerRange: TextRange, val contentRange: TextRange) : MarkdownMatch()
}

object MarkdownMatcher {

    private val headerRegex = Regex("""^(#{1,6}) (.*)$""")
    private val checkboxRegex = Regex("""^([ \t]*)- \[([ xX])] (.*)$""")
    private val bulletRegex = Regex("""^([ \t]*)[-+*] (?!\[[ xX]] ).*$""")

    private val escapeRegex = Regex("""\\[!-/:-@\[-`{-~]""")
    private val codeRegex = Regex("""`([^`\n]+)`""")
    private val linkRegex = Regex("""\[([^\]\n]+)]\(([^)\s]+)\)""")
    // ponytail: no balanced parens in bare URLs (GFM allows them); wrap such URLs as [text](url).
    private val bareUrlRegex = Regex("""https?://[^\s<>()\[\]]*[^\s<>()\[\].,;:!?'"*_~=]""")

    /** `_` only counts outside words (CommonMark), so snake_case_names stay plain. */
    private fun underscore(marker: String) =
        Regex("""(?<![\p{L}\p{N}_])$marker(?!\s)([^\n]+?)(?<!\s)$marker(?![\p{L}\p{N}_])""")

    // Order matters: longer delimiters claim their markers before the single-character ones.
    private val delimiters = listOf(
        InlineStyle.BOLD to Regex("""\*\*([^\n]+?)\*\*"""),
        InlineStyle.BOLD to underscore("__"),
        InlineStyle.STRIKE to Regex("""~~([^\n]+?)~~"""),
        InlineStyle.HIGHLIGHT to Regex("""==([^\n]+?)=="""),
        InlineStyle.ITALIC to Regex("""\*([^\n*]+?)\*"""),
        InlineStyle.ITALIC to underscore("_"),
        // Raw inline HTML is valid CommonMark, so <u> renders as underline in other editors too.
        InlineStyle.UNDERLINE to Regex("""<u>([^\n]+?)</u>""", RegexOption.IGNORE_CASE),
    )

    fun findMatches(text: String): List<MarkdownMatch> {
        val matches = mutableListOf<MarkdownMatch>()

        var lineStart = 0
        for (line in text.split("\n")) {
            matchLine(line, lineStart)?.let { matches.add(it) }
            lineStart += line.length + 1
        }

        // Claimed characters are blanked out so later, looser rules can't reuse them.
        val masked = text.toCharArray()
        fun mask(from: Int, to: Int) { for (i in from until to) masked[i] = '\u0000' }
        fun find(regex: Regex) = regex.findAll(String(masked)).toList()

        matches.filterIsInstance<MarkdownMatch.Bullet>().forEach { mask(it.markerRange.start, it.markerRange.start + 1) }
        for (m in find(escapeRegex)) {
            matches.add(MarkdownMatch.Escape(TextRange(m.range.first, m.range.first + 1)))
            mask(m.range.first, m.range.last + 1)
        }
        // Code spans are literal: nothing inside them is markdown.
        for (m in find(codeRegex)) {
            matches.add(inline(InlineStyle.CODE, m))
            mask(m.range.first, m.range.last + 1)
        }
        for (m in find(linkRegex)) {
            val content = m.groups[1]!!.range
            matches.add(
                MarkdownMatch.Link(
                    url = m.groupValues[2],
                    openMarker = TextRange(m.range.first, content.first),
                    content = TextRange(content.first, content.last + 1),
                    closeMarker = TextRange(content.last + 1, m.range.last + 1),
                )
            )
            mask(m.range.first, content.first)
            mask(content.last + 1, m.range.last + 1)
        }
        for (m in find(bareUrlRegex)) {
            val end = m.range.last + 1
            matches.add(MarkdownMatch.Link(m.value, TextRange(m.range.first, m.range.first), TextRange(m.range.first, end), TextRange(end, end)))
            mask(m.range.first, end)
        }
        for ((style, regex) in delimiters) {
            for (m in find(regex)) {
                val match = inline(style, m)
                matches.add(match)
                mask(match.openMarker.start, match.openMarker.end)
                mask(match.closeMarker.start, match.closeMarker.end)
            }
        }

        return matches
    }

    private fun inline(style: InlineStyle, m: MatchResult): MarkdownMatch.Inline {
        val content = m.groups[1]!!.range
        return MarkdownMatch.Inline(
            style = style,
            openMarker = TextRange(m.range.first, content.first),
            content = TextRange(content.first, content.last + 1),
            closeMarker = TextRange(content.last + 1, m.range.last + 1),
        )
    }

    private fun matchLine(line: String, lineStart: Int): MarkdownMatch? {
        checkboxRegex.find(line)?.let { m ->
            return MarkdownMatch.Checkbox(
                checked = m.groupValues[2].equals("x", ignoreCase = true),
                markerRange = TextRange(lineStart + m.groupValues[1].length + 2, lineStart + m.groupValues[1].length + 5),
                contentRange = TextRange(lineStart + m.groupValues[1].length + 6, lineStart + line.length),
            )
        }
        bulletRegex.find(line)?.let { m ->
            val start = lineStart + m.groupValues[1].length
            return MarkdownMatch.Bullet(markerRange = TextRange(start, start + 2))
        }
        headerRegex.find(line)?.let { m ->
            val level = m.groupValues[1].length
            return MarkdownMatch.Header(
                level = level,
                markerRange = TextRange(lineStart, lineStart + level + 1),
                contentRange = TextRange(lineStart + level + 1, lineStart + line.length),
            )
        }
        return null
    }
}
