package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    @Test
    fun roundTripKeepsTypes() {
        val settings = mapOf(
            "FIRST_OPEN" to false, "HOME_APPS_NUM" to 4, "FIRST_OPEN_TIME" to 1_700_000_000_000L,
            "TEXT_SIZE_SCALE" to 1.1f, "APP_NAME_1" to "Notes", "HIDDEN_APPS" to setOf("a|0", "b|0"),
        )
        val data = Backup.decode(Backup.encode(settings, "# todo\n- [ ] milk"))!!
        assertEquals(settings, data.settings)
        assertEquals("# todo\n- [ ] milk", data.scratchpad)
    }

    @Test
    fun plainNoteIsNotABackup() {
        assertNull(Backup.decode("just a note"))
        assertNull(Backup.decode("{\"some\": \"json note\"}"))
    }
}
