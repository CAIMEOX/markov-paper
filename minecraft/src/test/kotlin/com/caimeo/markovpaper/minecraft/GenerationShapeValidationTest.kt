package com.caimeo.markovpaper.minecraft

import kotlin.test.Test
import kotlin.test.assertFailsWith

class GenerationShapeValidationTest {
    @Test
    fun `oversized projected volume is rejected before grid allocation`() {
        val placement = GridPlacement.atBottomCorner(
            corner = BlockPoint(0, 64, 0),
            modelZIsUp = true,
        )

        assertFailsWith<IllegalArgumentException> {
            validateGenerationShape(
                placement = placement,
                sizeX = 1_000,
                sizeY = 1_000,
                sizeZ = 6,
                worldMinHeight = -64,
                worldMaxHeight = 320,
            )
        }
    }

    @Test
    fun `projected height must remain inside the world build range`() {
        val placement = GridPlacement.atBottomCorner(
            corner = BlockPoint(0, -64, 0),
            modelZIsUp = true,
        )

        assertFailsWith<IllegalArgumentException> {
            validateGenerationShape(
                placement = placement,
                sizeX = 41,
                sizeY = 41,
                sizeZ = 385,
                worldMinHeight = -64,
                worldMaxHeight = 320,
            )
        }
    }
}
