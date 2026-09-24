package com.caimeo.markovpaper.assemblage

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrontierAssemblagePlannerTest {
    @Test
    fun `bounded frontier growth produces one hundred connected deterministic instances`() {
        val root = filledAsset("test:root", Extent3i(9, 5, 7), "minecraft:stone")
        val palette = listOf(
            addition(filledAsset("test:house", Extent3i(7, 6, 9), "minecraft:oak_planks")),
            addition(filledAsset("test:tower", Extent3i(5, 11, 5), "minecraft:deepslate")),
            addition(filledAsset("test:bridge", Extent3i(13, 4, 5), "minecraft:bricks")),
        )
        val request = FrontierAssemblageRequest(
            root = root,
            palette = palette,
            targetInstances = 100,
            seed = 42,
            maxCandidateEvaluations = 6_400,
            maxSolidContactProbes = 409_600,
            maxNonParentOverlapRatio = 0.15,
        )

        val first = assertIs<FrontierAssemblageOutcome.Completed>(
            FrontierAssemblagePlanner.plan(request)
        ).plan
        val repeated = assertIs<FrontierAssemblageOutcome.Completed>(
            FrontierAssemblagePlanner.plan(request)
        ).plan

        assertEquals(100, first.instances.size)
        assertEquals(99, first.attachments.size)
        assertEquals(first, repeated)
        assertEquals(100, first.instances.map { it.id }.distinct().size)
        assertEquals(
            first.instances.drop(1).map { it.id }.toSet(),
            first.attachments.map { it.child }.toSet(),
        )
        assertTrue(first.candidateEvaluations in 99..6_400)
        assertTrue(first.solidContactProbes in 99..request.maxSolidContactProbes)
        assertTrue(first.instances.drop(1).map { it.source.rotation }.distinct().size >= 3)

        val parentByChild = first.attachments.associate { it.child to it.parent }
        for ((index, left) in first.instances.withIndex()) {
            for (right in first.instances.drop(index + 1)) {
                val directlyAttached = parentByChild[left.id] == right.id ||
                    parentByChild[right.id] == left.id
                val overlap = left.bounds.intersectionVolume(right.bounds)
                val smaller = min(left.bounds.volume, right.bounds.volume)
                val ratio = overlap.toDouble() / smaller
                if (directlyAttached) {
                    assertTrue(overlap > 0, "${left.id} and ${right.id} must touch")
                    assertTrue(ratio <= request.maxParentOverlapRatio)
                } else {
                    assertTrue(
                        ratio <= request.maxNonParentOverlapRatio,
                        "${left.id} and ${right.id} overlap too much",
                    )
                }
            }
        }
    }

    @Test
    fun `aabb overlap without solid contact cannot create a connected child`() {
        val root = centerOnlyAsset("test:root")
        val child = centerOnlyAsset("test:child")

        val outcome = assertIs<FrontierAssemblageOutcome.Exhausted>(
            FrontierAssemblagePlanner.plan(
                FrontierAssemblageRequest(
                    root = root,
                    palette = listOf(addition(child)),
                    targetInstances = 2,
                    seed = 7,
                    maxCandidateEvaluations = 6,
                    maxSolidContactProbes = 3,
                    candidatesPerFrontier = 1,
                )
            )
        )

        assertEquals(1, outcome.plan.instances.size)
        assertEquals(1, outcome.plan.candidateEvaluations)
        assertEquals(3, outcome.plan.solidContactProbes)
    }

    private fun addition(asset: StructureAsset): AssemblageAddition = AssemblageAddition(
        HorizontalRotation.entries.map { rotation ->
            OrientedStructureAsset(rotation, asset)
        }
    )

    private fun filledAsset(id: String, size: Extent3i, state: String): StructureAsset =
        StructureAsset(
            id = AssetId(id),
            size = size,
            palettes = listOf(
                AuthoredPalette(
                    buildMap {
                        for (x in 0 until size.x) {
                            for (y in 0 until size.y) {
                                for (z in 0 until size.z) {
                                    put(
                                        Vec3i(x, y, z),
                                        AuthoredCell.Block(BlockStateSpec(state)),
                                    )
                                }
                            }
                        }
                    }
                )
            ),
            tags = emptySet(),
        )

    private fun centerOnlyAsset(id: String): StructureAsset = StructureAsset(
        id = AssetId(id),
        size = Extent3i(5, 5, 5),
        palettes = listOf(
            AuthoredPalette(
                mapOf(
                    Vec3i(2, 2, 2) to AuthoredCell.Block(BlockStateSpec("minecraft:stone"))
                )
            )
        ),
        tags = emptySet(),
    )
}
