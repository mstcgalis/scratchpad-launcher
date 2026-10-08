package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTypingTest {
    @Test
    fun `typing into an empty formatted note preserves its markers`() {
        mapOf(
            MarkdownAction.BOLD to "**hello**",
            MarkdownAction.ITALIC to "*hello*",
            MarkdownAction.HEADING to "# hello",
            MarkdownAction.BULLET to "- hello",
            MarkdownAction.CHECKBOX to "- [ ] hello",
            MarkdownAction.INDENT to "  hello",
        ).forEach { (action, expected) ->
            assertEquals(expected, formatAndType("", 0, action, "hello"))
        }
    }

    @Test
    fun `formatting a blank line preserves other lines and indentation`() {
        assertEquals("first\n  - item\nlast", formatAndType("first\n  \nlast", 8, MarkdownAction.BULLET, "item"))
    }

    @Test
    fun `formatting at a cursor preserves the surrounding text`() {
        assertEquals("- one new two", formatAndType("one two", 4, MarkdownAction.BULLET, "new "))
        assertEquals("- [ ] one new two", formatAndType("- one two", 6, MarkdownAction.CHECKBOX, "new "))
        assertEquals("new one", formatAndType("- one", 0, MarkdownAction.BULLET, "new "))
    }

    private fun formatAndType(source: String, cursor: Int, action: MarkdownAction, typed: String): String {
        val edit = MarkdownFormatter.edit(source, cursor, cursor, action)
        val formatted = source.replaceRange(edit.start, edit.end, edit.replacement)
        assertEquals(edit.selectionStart, edit.selectionEnd)
        return formatted.replaceRange(edit.selectionStart, edit.selectionEnd, typed)
    }
}
