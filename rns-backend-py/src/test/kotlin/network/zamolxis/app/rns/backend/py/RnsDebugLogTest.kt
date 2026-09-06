package network.zamolxis.app.rns.backend.py

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RnsDebugLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `disabled when marker absent`() {
        assertFalse(RnsDebugLog.isEnabled(tmp.root))
    }

    @Test
    fun `enabled when marker present`() {
        File(tmp.root, RnsDebugLog.MARKER_FILE).writeText("")
        assertTrue(RnsDebugLog.isEnabled(tmp.root))
    }

    @Test
    fun `bare touch (empty marker) uses the default level`() {
        File(tmp.root, RnsDebugLog.MARKER_FILE).writeText("")
        assertEquals(RnsDebugLog.DEFAULT_LEVEL, RnsDebugLog.level(tmp.root))
    }

    @Test
    fun `marker with a valid level uses it`() {
        File(tmp.root, RnsDebugLog.MARKER_FILE).writeText("7\n")
        assertEquals(7, RnsDebugLog.level(tmp.root))
        File(tmp.root, RnsDebugLog.MARKER_FILE).writeText("0")
        assertEquals(0, RnsDebugLog.level(tmp.root))
    }

    @Test
    fun `out-of-range or garbage level falls back to default`() {
        File(tmp.root, RnsDebugLog.MARKER_FILE).writeText("99")
        assertEquals(RnsDebugLog.DEFAULT_LEVEL, RnsDebugLog.level(tmp.root))
        File(tmp.root, RnsDebugLog.MARKER_FILE).writeText("loud")
        assertEquals(RnsDebugLog.DEFAULT_LEVEL, RnsDebugLog.level(tmp.root))
    }

    @Test
    fun `logFile sits in the config dir`() {
        assertEquals(File(tmp.root, RnsDebugLog.LOG_FILE), RnsDebugLog.logFile(tmp.root))
    }
}
