package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProceduralSceneTest {
    @Test
    fun `procedural cell retains model seed symbol and source coordinate`() {
        val state = BlockStateSpec("minecraft:gray_stained_glass")
        val provenance = SceneProvenance.Procedural(
            model = "apartemazements",
            seed = 42,
            symbol = 'R',
            sourceCell = Vec3i(1, 2, 3),
        )
        val contribution = SceneContribution(
            provenance = provenance,
            kind = SceneContributionKind.PROCEDURAL_BLOCK,
            state = state,
        )
        val cell = SceneCell(state, contribution, listOf(contribution))

        assertEquals(provenance, contribution.provenance)
        assertTrue(cell.sources.isEmpty())
    }
}
