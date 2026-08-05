package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMatcherTest {

    @Test
    fun `plain text produces no matches`() {
        val matches = MarkdownMatcher.findMatches("just some plain text, nothing special")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `single hash header matches level 1`() {
        val matches = MarkdownMatcher.findMatches("# Title")
        val header = matches.single() as MarkdownMatch.Header
        assertEquals(1, header.level)
        assertEquals(TextRange(0, 2), header.markerRange)
        assertEquals(TextRange(2, 7), header.contentRange)
    }

    @Test
    fun `six hashes produce level 6 header`() {
        val matches = MarkdownMatcher.findMatches("###### Title")
        val header = matches.single() as MarkdownMatch.Header
        assertEquals(6, header.level)
        assertEquals(TextRange(0, 7), header.markerRange)
        assertEquals(TextRange(7, 12), header.contentRange)
    }

    @Test
    fun `seven hashes is not a header`() {
        val matches = MarkdownMatcher.findMatches("####### Title")
        assertTrue(matches.none { it is MarkdownMatch.Header })
    }

    @Test
    fun `hash without following space is not a header`() {
        val matches = MarkdownMatcher.findMatches("#hashtag not a header")
        assertTrue(matches.none { it is MarkdownMatch.Header })
    }

    @Test
    fun `bold text matches markers and content`() {
        val matches = MarkdownMatcher.findMatches("this is **bold** text")
        val bold = matches.single() as MarkdownMatch.Bold
        assertEquals(TextRange(8, 10), bold.openMarker)
        assertEquals(TextRange(10, 14), bold.content)
        assertEquals(TextRange(14, 16), bold.closeMarker)
    }

    @Test
    fun `italic text matches markers and content`() {
        val matches = MarkdownMatcher.findMatches("this is *italic* text")
        val italic = matches.single() as MarkdownMatch.Italic
        assertEquals(TextRange(8, 9), italic.openMarker)
        assertEquals(TextRange(9, 15), italic.content)
        assertEquals(TextRange(15, 16), italic.closeMarker)
    }

    @Test
    fun `bold markers are not also matched as italic`() {
        val matches = MarkdownMatcher.findMatches("**bold** and *italic*")
        assertEquals(1, matches.count { it is MarkdownMatch.Bold })
        val italics = matches.filterIsInstance<MarkdownMatch.Italic>()
        assertEquals(1, italics.size)
        assertEquals("italic", "**bold** and *italic*".substring(italics[0].content.start, italics[0].content.end))
    }

    @Test
    fun `bullet line dims leading dash and space`() {
        val matches = MarkdownMatcher.findMatches("- item one")
        val bullet = matches.single() as MarkdownMatch.Bullet
        assertEquals(TextRange(0, 2), bullet.markerRange)
    }

    @Test
    fun `unchecked checkbox matches glyph and content ranges`() {
        val matches = MarkdownMatcher.findMatches("- [ ] todo")
        val checkbox = matches.single() as MarkdownMatch.Checkbox
        assertEquals(false, checkbox.checked)
        assertEquals(TextRange(2, 5), checkbox.markerRange)
        assertEquals(TextRange(6, 10), checkbox.contentRange)
    }

    @Test
    fun `checked checkbox matches glyph and content ranges`() {
        val matches = MarkdownMatcher.findMatches("- [x] done")
        val checkbox = matches.single() as MarkdownMatch.Checkbox
        assertEquals(true, checkbox.checked)
        assertEquals(TextRange(2, 5), checkbox.markerRange)
        assertEquals(TextRange(6, 10), checkbox.contentRange)
    }

    @Test
    fun `checkbox line is not also matched as plain bullet`() {
        val matches = MarkdownMatcher.findMatches("- [ ] todo")
        assertTrue(matches.none { it is MarkdownMatch.Bullet })
    }

    @Test
    fun `multiple rules mixed on one line`() {
        val matches = MarkdownMatcher.findMatches("- **bold** and *italic* item")
        assertTrue(matches.any { it is MarkdownMatch.Bullet })
        assertTrue(matches.any { it is MarkdownMatch.Bold })
        assertTrue(matches.any { it is MarkdownMatch.Italic })
    }

    @Test
    fun `header and bold across separate lines both match with correct offsets`() {
        val text = "# Title\n**bold** line"
        val matches = MarkdownMatcher.findMatches(text)
        val header = matches.filterIsInstance<MarkdownMatch.Header>().single()
        assertEquals(TextRange(0, 2), header.markerRange)
        val bold = matches.filterIsInstance<MarkdownMatch.Bold>().single()
        assertEquals(TextRange(8, 10), bold.openMarker)
        assertEquals(TextRange(10, 14), bold.content)
    }

    @Test
    fun `indented dash is not treated as a bullet`() {
        val matches = MarkdownMatcher.findMatches("  - not a bullet")
        assertTrue(matches.none { it is MarkdownMatch.Bullet })
    }
}
