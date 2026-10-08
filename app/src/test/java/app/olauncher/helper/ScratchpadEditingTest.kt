package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchpadEditingTest {
    @Test
    fun `asterisk list markers do not consume inline emphasis`() {
        val source = "  * item *italic*"
        val match = MarkdownMatcher.findMatches(source).filterIsInstance<MarkdownMatch.Italic>().single()
        assertEquals("italic", source.substring(match.content.start, match.content.end))
    }

    @Test
    fun `nested checkboxes preserve indentation and point to the correct toggle character`() {
        val source = "- parent\n  - [ ] child\n\t- [X] done"
        val matches = MarkdownMatcher.findMatches(source).filterIsInstance<MarkdownMatch.Checkbox>()
        assertEquals(2, matches.size)
        assertFalse(matches[0].checked)
        assertTrue(matches[1].checked)
        assertEquals(' ', source[matches[0].markerRange.start + 1])
        assertEquals('X', source[matches[1].markerRange.start + 1])
        assertEquals("child", source.substring(matches[0].contentRange.start, matches[0].contentRange.end))
    }

    @Test
    fun `emphasis wraps selection and toggles off without losing text`() {
        val edit = MarkdownFormatter.edit("one two", 4, 7, MarkdownAction.BOLD)
        val formatted = "one two".replaceRange(edit.start, edit.end, edit.replacement)
        assertEquals("one **two**", formatted)
        assertEquals("two", formatted.substring(edit.selectionStart, edit.selectionEnd))
        val undo = MarkdownFormatter.edit(formatted, edit.selectionStart, edit.selectionEnd, MarkdownAction.BOLD)
        assertEquals("one two", formatted.replaceRange(undo.start, undo.end, undo.replacement))
    }

    @Test
    fun `empty note can receive inline and line formatting`() {
        assertEquals(MarkdownEdit(0, 0, "****", 2, 2), MarkdownFormatter.edit("", 0, 0, MarkdownAction.BOLD))
        assertEquals("- [ ] ", MarkdownFormatter.edit("", 0, 0, MarkdownAction.CHECKBOX).replacement)
    }

    @Test
    fun `indent affects selected lines without changing following line`() {
        val source = "- parent\n- child\n- sibling"
        val edit = MarkdownFormatter.edit(source, 9, 17, MarkdownAction.INDENT)
        assertEquals("- parent\n  - child\n- sibling", source.replaceRange(edit.start, edit.end, edit.replacement))
        val outdent = MarkdownFormatter.edit("  - child", 0, 9, MarkdownAction.OUTDENT)
        assertEquals("- child", outdent.replacement)
    }

    @Test
    fun `line formatting replaces existing marker and retains indentation`() {
        assertEquals("  - [ ] child", MarkdownFormatter.edit("  - child", 5, 5, MarkdownAction.CHECKBOX).replacement)
        assertEquals("  child", MarkdownFormatter.edit("  - child", 5, 5, MarkdownAction.BULLET).replacement)
    }
}
