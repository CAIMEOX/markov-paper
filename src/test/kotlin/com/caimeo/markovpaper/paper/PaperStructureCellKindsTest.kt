package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.ImportedCellKind
import kotlin.test.Test
import kotlin.test.assertEquals

class PaperStructureCellKindsTest {
    @Test
    fun `minecraft control and transparent materials keep distinct imported kinds`() {
        assertEquals(
            ImportedCellKind.AUTHORED_AIR,
            PaperStructureCellKinds.classify("minecraft:air"),
        )
        assertEquals(
            ImportedCellKind.AUTHORED_AIR,
            PaperStructureCellKinds.classify("minecraft:cave_air"),
        )
        assertEquals(
            ImportedCellKind.STRUCTURE_VOID,
            PaperStructureCellKinds.classify("minecraft:structure_void"),
        )
        assertEquals(
            ImportedCellKind.JIGSAW,
            PaperStructureCellKinds.classify("minecraft:jigsaw"),
        )
        assertEquals(
            ImportedCellKind.STRUCTURE_BLOCK,
            PaperStructureCellKinds.classify("minecraft:structure_block"),
        )
        assertEquals(
            ImportedCellKind.BLOCK,
            PaperStructureCellKinds.classify("minecraft:oak_stairs"),
        )
    }
}
