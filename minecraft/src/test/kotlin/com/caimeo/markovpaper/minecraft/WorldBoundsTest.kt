package com.caimeo.markovpaper.minecraft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldBoundsTest {
    @Test
    fun `bounds enclose a z-up model from its bottom corner`() {
        val placement = GridPlacement.atBottomCorner(
            corner = BlockPoint(10, 20, 30),
            modelZIsUp = true,
        )

        val bounds = WorldBounds.fromModel(
            placement = placement,
            sizeX = 4,
            sizeY = 5,
            sizeZ = 6,
        )

        assertEquals(BlockPoint(10, 20, 30), bounds.minimum)
        assertEquals(BlockPoint(13, 25, 34), bounds.maximum)
        assertTrue(bounds.edgePoints(maxSamplesPerEdge = 3).all(bounds::contains))
    }
}
