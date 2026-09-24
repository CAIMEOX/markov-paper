package com.caimeo.markovpaper.paper

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugBuildStampTest {
    @Test
    fun `completion requires current schema revision expected count and zero failures`() {
        val complete = DebugBuildStamp(
            schema = 1,
            revision = "minecraft-26.2",
            expected = 483,
            placed = 483,
            failed = 0,
        )
        val failed = complete.copy(placed = 482, failed = 1)

        assertTrue(complete.isCurrentComplete(1, "minecraft-26.2", 483))
        assertFalse(failed.isCurrentComplete(1, "minecraft-26.2", 483))
        assertFalse(complete.isCurrentComplete(2, "minecraft-26.2", 483))
        assertFalse(complete.isCurrentComplete(1, "minecraft-26.3", 483))
        assertTrue(DebugBuildStamp.parse(complete.encode()) == complete)
    }

    @Test
    fun `placement starts only when every expected descriptor loaded`() {
        val ready = DebugBuildStamp(1, "minecraft-26.2", expected = 483, placed = 0, failed = 0)
        val failed = ready.copy(failed = 1)

        assertTrue(ready.allowsPlacementOf(483))
        assertFalse(ready.allowsPlacementOf(482))
        assertFalse(failed.allowsPlacementOf(482))
        assertTrue(failed.canRetryInPlace())
        assertFalse(failed.copy(placed = 482).canRetryInPlace())
    }
}
