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
        val bold = matches.inline(InlineStyle.BOLD).single()
        assertEquals(TextRange(8, 10), bold.openMarker)
        assertEquals(TextRange(10, 14), bold.content)
        assertEquals(TextRange(14, 16), bold.closeMarker)
    }

    @Test
    fun `italic text matches markers and content`() {
        val matches = MarkdownMatcher.findMatches("this is *italic* text")
        val italic = matches.inline(InlineStyle.ITALIC).single()
        assertEquals(TextRange(8, 9), italic.openMarker)
        assertEquals(TextRange(9, 15), italic.content)
        assertEquals(TextRange(15, 16), italic.closeMarker)
    }

    @Test
    fun `bold markers are not also matched as italic`() {
        val matches = MarkdownMatcher.findMatches("**bold** and *italic*")
        assertEquals(1, matches.inline(InlineStyle.BOLD).size)
        val italics = matches.inline(InlineStyle.ITALIC)
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
        assertTrue(matches.inline(InlineStyle.BOLD).isNotEmpty())
        assertTrue(matches.inline(InlineStyle.ITALIC).isNotEmpty())
    }

    @Test
    fun `header and bold across separate lines both match with correct offsets`() {
        val text = "# Title\n**bold** line"
        val matches = MarkdownMatcher.findMatches(text)
        val header = matches.filterIsInstance<MarkdownMatch.Header>().single()
        assertEquals(TextRange(0, 2), header.markerRange)
        val bold = matches.inline(InlineStyle.BOLD).single()
        assertEquals(TextRange(8, 10), bold.openMarker)
        assertEquals(TextRange(10, 14), bold.content)
    }

    @Test
    fun `indented dash retains its nesting before the bullet marker`() {
        val matches = MarkdownMatcher.findMatches("  - nested bullet")
        assertEquals(TextRange(2, 4), matches.filterIsInstance<MarkdownMatch.Bullet>().single().markerRange)
    }

    @Test
    fun `html u tag matches underline markers and content`() {
        val u = MarkdownMatcher.findMatches("an <u>under</u> line").inline(InlineStyle.UNDERLINE).single()
        assertEquals(TextRange(3, 6), u.openMarker)
        assertEquals(TextRange(6, 11), u.content)
        assertEquals(TextRange(11, 15), u.closeMarker)
    }

    @Test
    fun `underline nests inside bold and does not span lines`() {
        assertEquals(2, MarkdownMatcher.findMatches("**<u>both</u>**").size)
        assertTrue(MarkdownMatcher.findMatches("<u>open\nclose</u>").isEmpty())
    }

    @Test
    fun `underline action toggles u tags`() {
        val on = MarkdownFormatter.edit("word", 0, 4, MarkdownAction.UNDERLINE)
        assertEquals("<u>word</u>", on.replacement)
        val off = MarkdownFormatter.edit("<u>word</u>", on.selectionStart, on.selectionEnd, MarkdownAction.UNDERLINE)
        assertEquals(MarkdownEdit(0, 11, "word", 0, 4), off)
    }

    private fun MarkdownMatch.Inline.text(source: String) = source.substring(content.start, content.end)

    @Test
    fun `underscores italicise and bold only outside words`() {
        val source = "_it_ __bold__ snake_case_name"
        val matches = MarkdownMatcher.findMatches(source)
        assertEquals("it", matches.inline(InlineStyle.ITALIC).single().text(source))
        assertEquals("bold", matches.inline(InlineStyle.BOLD).single().text(source))
    }

    @Test
    fun `strike highlight and code spans match`() {
        val source = "~~gone~~ ==mark== `x`"
        val matches = MarkdownMatcher.findMatches(source)
        assertEquals("gone", matches.inline(InlineStyle.STRIKE).single().text(source))
        assertEquals("mark", matches.inline(InlineStyle.HIGHLIGHT).single().text(source))
        assertEquals(TextRange(19, 20), matches.inline(InlineStyle.CODE).single().content)
    }

    @Test
    fun `code spans and escapes are literal`() {
        val matches = MarkdownMatcher.findMatches("`*not* __bold__` \\*plain\\*")
        assertEquals(1, matches.filterIsInstance<MarkdownMatch.Inline>().size)
        assertEquals(listOf(TextRange(17, 18), TextRange(24, 25)), matches.filterIsInstance<MarkdownMatch.Escape>().map { it.markerRange })
    }

    @Test
    fun `emphasis nests inside bold`() {
        val source = "**a *b* c**"
        assertEquals("b", MarkdownMatcher.findMatches(source).inline(InlineStyle.ITALIC).single().text(source))
    }

    @Test
    fun `markdown link hides url and keeps styled text`() {
        val link = MarkdownMatcher.findMatches("see [**docs**](https://x.org/a_b_c)").filterIsInstance<MarkdownMatch.Link>().single()
        assertEquals("https://x.org/a_b_c", link.url)
        assertEquals(TextRange(4, 5), link.openMarker)
        assertEquals(TextRange(5, 13), link.content)
        assertEquals(TextRange(13, 35), link.closeMarker)
        assertEquals(1, MarkdownMatcher.findMatches("see [**docs**](https://x.org/a_b_c)").inline(InlineStyle.BOLD).size)
    }

    @Test
    fun `bare url is a link without trailing punctuation or emphasis`() {
        val matches = MarkdownMatcher.findMatches("go to https://x.org/snake_case_path. now")
        assertEquals("https://x.org/snake_case_path", matches.filterIsInstance<MarkdownMatch.Link>().single().url)
        assertTrue(matches.none { it is MarkdownMatch.Inline })
    }

    @Test
    fun `link action wraps selection or url`() {
        assertEquals(MarkdownEdit(0, 4, "[word]()", 7, 7), MarkdownFormatter.edit("word", 0, 4, MarkdownAction.LINK))
        assertEquals(MarkdownEdit(0, 9, "[](https://x)", 1, 1), MarkdownFormatter.edit("https://x", 0, 9, MarkdownAction.LINK))
    }
}

internal fun List<MarkdownMatch>.inline(style: InlineStyle) = filterIsInstance<MarkdownMatch.Inline>().filter { it.style == style }
