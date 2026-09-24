package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoxelResourceTest {
    @Test
    fun `vox loader preserves dimensions emptiness and legend ordinals`() {
        val stream = requireNotNull(
            javaClass.getResourceAsStream("/markovjunior/rules/CarmaTower/block_2_4x3.vox")
        )

        val pattern = VoxelResource.read(stream, legend = "rB*LTR")

        assertEquals(12, pattern.sizeX)
        assertEquals(3, pattern.sizeY)
        assertEquals(4, pattern.sizeZ)
        assertEquals(144, pattern.cells.size)
        assertTrue(pattern.cells.all { it in "rB*LTR" })
    }
}
