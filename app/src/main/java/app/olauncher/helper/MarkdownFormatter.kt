package app.olauncher.helper

enum class MarkdownAction { BOLD, ITALIC, HEADING, BULLET, CHECKBOX, INDENT, OUTDENT }

data class MarkdownEdit(val start: Int, val end: Int, val replacement: String, val selectionStart: Int, val selectionEnd: Int)

object MarkdownFormatter {
    private val prefix = Regex("""^(#{1,6} |[-+*] (?:\[[ xX]] )?)""")

    fun edit(text: String, start: Int, end: Int, action: MarkdownAction): MarkdownEdit {
        require(start in 0..text.length && end in start..text.length)
        if (action == MarkdownAction.BOLD || action == MarkdownAction.ITALIC) {
            val marker = if (action == MarkdownAction.BOLD) "**" else "*"
            val size = marker.length
            if (start >= size && end + size <= text.length && text.substring(start - size, start) == marker
                && text.substring(end, end + size) == marker) {
                return MarkdownEdit(start - size, end + size, text.substring(start, end), start - size, end - size)
            }
            return MarkdownEdit(start, end, marker + text.substring(start, end) + marker, start + size, end + size)
        }

        val from = text.lastIndexOf('\n', start - 1) + 1
        val lastSelected = if (end > start && text[end - 1] == '\n') end - 1 else end
        val to = text.indexOf('\n', lastSelected).let { if (it < 0) text.length else it }
        val replacement = text.substring(from, to).split('\n').joinToString("\n") { line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val body = line.substring(indent.length)
            when (action) {
                MarkdownAction.INDENT -> "  $line"
                MarkdownAction.OUTDENT -> if (line.startsWith('\t')) line.drop(1) else line.drop(indent.take(2).length)
                else -> {
                    val current = prefix.find(body)?.value.orEmpty()
                    val marker = when (action) {
                        MarkdownAction.HEADING -> "# "
                        MarkdownAction.CHECKBOX -> "- [ ] "
                        else -> "- "
                    }
                    indent + (if (current == marker) "" else marker) + body.removePrefix(current)
                }
            }
        }
        if (start == end) {
            val cursor = (start + replacement.length - (to - from)).coerceIn(from, from + replacement.length)
            return MarkdownEdit(from, to, replacement, cursor, cursor)
        }
        return MarkdownEdit(from, to, replacement, from, from + replacement.length)
    }
}
