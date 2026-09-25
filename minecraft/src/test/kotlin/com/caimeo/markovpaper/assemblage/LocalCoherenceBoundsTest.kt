package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertTrue

class LocalCoherenceBoundsTest {
    @Test
    fun `doorway inference never writes beyond the declared Scene extent`() {
        val size = Extent3i(2, 4, 1)
        val state = BlockStateSpec("minecraft:stone")
        val cells = buildMap {
            for (x in 0 until size.x) {
                for (y in 0 until size.y) {
                    val position = Vec3i(x, y, 0)
                    val contribution = SceneContribution(
                        provenance = SceneProvenance.Authored(SourceInstanceId("source"), AssetId("test:wall"), position),
                        kind = SceneContributionKind.BLOCK,
                        state = state,
                    )
                    put(position, SceneCell(state, contribution, listOf(contribution)))
                }
            }
        }
        val collisionPositions = (0 until size.y).mapTo(LinkedHashSet()) { y ->
            Vec3i(size.x - 1, y, 0)
        }

        val result = LocalCoherenceKernel.reconcile(
            scene = SceneSnapshot(size, cells),
            seam = SeamBandGeometry(
                axis = 0,
                threshold = size.x - 1,
                collisionPositions = collisionPositions,
            ),
        )

        assertTrue(result.scene.cells.keys.all(size::contains))
    }
}
