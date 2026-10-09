package app.olauncher.helper

enum class MarkdownAction { BOLD, ITALIC, UNDERLINE, STRIKE, HIGHLIGHT, CODE, LINK, HEADING, BULLET, CHECKBOX, INDENT, OUTDENT }

data class MarkdownEdit(val start: Int, val end: Int, val replacement: String, val selectionStart: Int, val selectionEnd: Int)

object MarkdownFormatter {
    private val prefix = Regex("""^(#{1,6} |[-+*] (?:\[[ xX]] )?)""")

    fun edit(text: String, start: Int, end: Int, action: MarkdownAction): MarkdownEdit {
        require(start in 0..text.length && end in start..text.length)
        if (action == MarkdownAction.LINK) {
            val selected = text.substring(start, end)
            // A selected URL becomes the target and the cursor goes to the (empty) link text; otherwise the reverse.
            if (selected.matches(Regex("""https?://\S+"""))) return MarkdownEdit(start, end, "[]($selected)", start + 1, start + 1)
            val cursor = if (selected.isEmpty()) start + 1 else end + 3
            return MarkdownEdit(start, end, "[$selected]()", cursor, cursor)
        }
        val (open, close) = when (action) {
            MarkdownAction.BOLD -> "**" to "**"
            MarkdownAction.ITALIC -> "*" to "*"
            MarkdownAction.UNDERLINE -> "<u>" to "</u>"
            MarkdownAction.STRIKE -> "~~" to "~~"
            MarkdownAction.HIGHLIGHT -> "==" to "=="
            MarkdownAction.CODE -> "`" to "`"
            else -> "" to ""
        }
        if (open.isNotEmpty()) {
            if (start >= open.length && text.startsWith(open, start - open.length) && text.startsWith(close, end)) {
                return MarkdownEdit(start - open.length, end + close.length, text.substring(start, end), start - open.length, end - open.length)
            }
            return MarkdownEdit(start, end, open + text.substring(start, end) + close, start + open.length, end + open.length)
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
                        // Checkbox cycles: none -> [ ] -> [x] -> none, so items can be ticked while editing.
                        MarkdownAction.CHECKBOX -> when {
                            current.endsWith("[ ] ") -> "- [x] "
                            current.endsWith("] ") -> ""
                            else -> "- [ ] "
                        }
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

    private val listItem = Regex("""^([ \t]*)([-+*] )(\[[ xX]] )?(.*)$""")
    private val doneItem = Regex("""^[ \t]*[-+*] \[[xX]] .*(\n|$)""", RegexOption.MULTILINE)

    /** After a newline was typed at [newline]: carry the list marker onto the new line, or end the list on an empty item. */
    fun continueList(text: String, newline: Int): MarkdownEdit? {
        val from = text.lastIndexOf('\n', newline - 1) + 1
        val m = listItem.find(text.substring(from, newline)) ?: return null
        val (indent, bullet, box, body) = m.destructured
        if (body.isBlank()) return MarkdownEdit(from, newline + 1, "", from, from)
        val marker = indent + bullet + (if (box.isEmpty()) "" else "[ ] ")
        return MarkdownEdit(newline + 1, newline + 1, marker, newline + 1 + marker.length, newline + 1 + marker.length)
    }

    /** Removes every ticked checkbox line. */
    fun clearDone(text: String): String = doneItem.replace(text, "").removeSuffix("\n").let { if (text.endsWith("\n")) "$it\n" else it }
}
