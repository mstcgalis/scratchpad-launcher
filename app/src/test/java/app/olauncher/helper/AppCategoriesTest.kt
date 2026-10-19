package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppCategoriesTest {
    @Test fun leadingEmojiIsTheCategory() {
        assertEquals("🎵", AppCategories.of("🎵 Spotify"))
        assertEquals("🎵", AppCategories.of("🎵Spotify"))
        assertEquals("❤", AppCategories.of("❤ Health"))
    }

    @Test fun plainLabelsHaveNone() {
        assertNull(AppCategories.of("Spotify"))
        assertNull(AppCategories.of("1Password"))
        assertNull(AppCategories.of("(beta) App"))
        assertNull(AppCategories.of(""))
    }

    @Test fun allIsDistinctInOrder() {
        assertEquals(listOf("📷", "🎵"), AppCategories.all(listOf("📷 Camera", "Maps", "🎵 Spotify", "📷 Photos")))
    }
}
