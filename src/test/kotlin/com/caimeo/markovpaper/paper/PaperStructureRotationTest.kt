package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.HorizontalRotation
import org.bukkit.block.structure.StructureRotation
import kotlin.test.Test
import kotlin.test.assertEquals

class PaperStructureRotationTest {
    @Test
    fun `horizontal rotations map to matching minecraft structure rotations`() {
        assertEquals(StructureRotation.NONE, HorizontalRotation.NONE.toPaperRotation())
        assertEquals(
            StructureRotation.CLOCKWISE_90,
            HorizontalRotation.CLOCKWISE_90.toPaperRotation(),
        )
        assertEquals(
            StructureRotation.CLOCKWISE_180,
            HorizontalRotation.CLOCKWISE_180.toPaperRotation(),
        )
        assertEquals(
            StructureRotation.COUNTERCLOCKWISE_90,
            HorizontalRotation.CLOCKWISE_270.toPaperRotation(),
        )
    }
}
