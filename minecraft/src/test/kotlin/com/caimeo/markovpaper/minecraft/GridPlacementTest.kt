package com.caimeo.markovpaper.minecraft

import kotlin.test.Test
import kotlin.test.assertEquals

class GridPlacementTest {
    @Test
    fun `three dimensional model z axis maps to minecraft y`() {
        val placement = GridPlacement(
            originX = 10,
            originY = 20,
            originZ = 30,
            modelZIsUp = true,
        )

        assertEquals(BlockPoint(10, 21, 30), placement.worldPosition(0, 0, 1))
        assertEquals(BlockPoint(10, 20, 31), placement.worldPosition(0, 1, 0))
    }
}
