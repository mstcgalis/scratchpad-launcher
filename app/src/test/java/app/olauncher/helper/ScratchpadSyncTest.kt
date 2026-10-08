package app.olauncher.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchpadSyncTest {
    @Test
    fun blankNoteNeverWipesFile() {
        assertFalse(ScratchpadSync.shouldWrite("todo\n", ""))
        assertFalse(ScratchpadSync.shouldWrite("todo\n", "  \n"))
    }

    @Test
    fun writesRealChangesOnly() {
        assertTrue(ScratchpadSync.shouldWrite("todo\n", "done\n"))
        assertTrue(ScratchpadSync.shouldWrite("", "new note"))
        assertFalse(ScratchpadSync.shouldWrite("same", "same"))
    }
}
