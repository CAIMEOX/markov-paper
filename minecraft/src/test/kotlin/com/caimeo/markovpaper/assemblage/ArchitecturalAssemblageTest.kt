package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArchitecturalAssemblageTest {
    @Test
    fun `authored air from one source does not erase another source block`() {
        val first = linearAsset(
            id = "test:first",
            states = listOf("minecraft:stone", "minecraft:stone", "minecraft:stone"),
        )
        val second = StructureAsset(
            id = AssetId("test:second"),
            size = Extent3i(2, 1, 1),
            palettes = listOf(
                AuthoredPalette(
                    mapOf(
                        Vec3i(0, 0, 0) to AuthoredCell.Block(
                            BlockStateSpec("minecraft:deepslate")
                        ),
                        Vec3i(1, 0, 0) to AuthoredCell.AuthoredAir,
                    )
                )
            ),
            tags = emptySet(),
        )
        val outcome = ArchitecturalAssemblage.compose(
            AssemblageRequest(
                first = first,
                secondVariants = listOf(
                    OrientedStructureAsset(HorizontalRotation.NONE, second)
                ),
                placement = AssemblagePlacement.Fixed(
                    rotation = HorizontalRotation.NONE,
                    offset = Vec3i(1, 0, 0),
                ),
                seed = 7,
            )
        )

        val result = assertIs<AssemblageOutcome.Generated>(outcome).result

        assertEquals(
            BlockStateSpec("minecraft:stone"),
            result.scene.cells.getValue(Vec3i(2, 0, 0)).state,
        )
        assertEquals(
            setOf(first.id, second.id),
            result.scene.cells.getValue(Vec3i(1, 0, 0)).sources,
        )
        val collision = result.scene.cells.getValue(Vec3i(1, 0, 0))
        assertEquals(
            setOf(SourceInstanceId("first"), SourceInstanceId("second")),
            collision.contributions.map { (it.provenance as? SceneProvenance.Authored)?.instance }.toSet(),
        )
        assertTrue(collision.selected in collision.contributions)
        assertEquals(1, result.report.solidCollisionCount)
    }

    @Test
    fun `search creates deterministic continuous overlap near the requested ratio`() {
        val first = filledAsset("test:first", "minecraft:stone", Extent3i(4, 1, 4))
        val second = filledAsset("test:second", "minecraft:deepslate", Extent3i(4, 1, 4))
        val request = AssemblageRequest(
            first = first,
            secondVariants = listOf(
                OrientedStructureAsset(HorizontalRotation.NONE, second)
            ),
            placement = AssemblagePlacement.Search(targetSolidOverlap = 0.25),
            seed = 42,
        )

        val firstResult = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(request)
        ).result
        val secondResult = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(request)
        ).result

        assertEquals(firstResult, secondResult)
        assertEquals(0.25, firstResult.report.solidOverlapRatio)
        assertEquals(4, firstResult.report.solidCollisionCount)
        assertEquals(
            4,
            firstResult.scene.cells.values.count { it.sources == setOf(first.id, second.id) },
        )
        val states = firstResult.scene.cells.values.map(SceneCell::state).toSet()
        assertTrue(BlockStateSpec("minecraft:stone") in states)
        assertTrue(BlockStateSpec("minecraft:deepslate") in states)
    }

    @Test
    fun `repeated asset keeps two distinct source instances`() {
        val asset = filledAsset("test:repeated", "minecraft:stone", Extent3i(2, 1, 1))
        val result = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageRequest(
                    first = asset,
                    secondVariants = listOf(
                        OrientedStructureAsset(HorizontalRotation.NONE, asset)
                    ),
                    placement = AssemblagePlacement.Fixed(
                        HorizontalRotation.NONE,
                        Vec3i(1, 0, 0),
                    ),
                    seed = 3,
                )
            )
        ).result

        val collision = result.scene.cells.values.single {
            it.contributions.count { contribution ->
                contribution.kind == SceneContributionKind.BLOCK
            } == 2
        }

        assertEquals(setOf(asset.id), collision.sources)
        assertEquals(
            setOf(SourceInstanceId("first"), SourceInstanceId("second")),
            collision.contributions.map { (it.provenance as? SceneProvenance.Authored)?.instance }.toSet(),
        )
    }

    @Test
    fun `collision winners form one monotone cut instead of per-cell noise`() {
        val first = filledAsset("test:first", "minecraft:stone", Extent3i(4, 1, 4))
        val second = filledAsset("test:second", "minecraft:deepslate", Extent3i(4, 1, 4))
        val result = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageRequest(
                    first = first,
                    secondVariants = listOf(
                        OrientedStructureAsset(HorizontalRotation.NONE, second)
                    ),
                    placement = AssemblagePlacement.Fixed(
                        HorizontalRotation.NONE,
                        Vec3i(2, 0, 0),
                    ),
                    seed = 99,
                )
            )
        ).result
        val collisionByX = result.scene.cells
            .filterValues { cell ->
                cell.contributions.count { it.kind == SceneContributionKind.BLOCK } == 2
            }
            .entries
            .groupBy { it.key.x }

        assertEquals(2, collisionByX.size)
        for (column in collisionByX.values) {
            assertEquals(
                listOf("first", "first", "second", "second"),
                column.sortedBy { it.key.z }.map { (it.value.selected?.provenance as? SceneProvenance.Authored)?.instance?.value },
            )
        }
    }

    @Test
    fun `search rejects work above its solid pair budget before planning`() {
        val first = filledAsset("test:first", "minecraft:stone", Extent3i(4, 1, 4))
        val second = filledAsset("test:second", "minecraft:deepslate", Extent3i(4, 1, 4))

        val outcome = ArchitecturalAssemblage.compose(
            AssemblageRequest(
                first = first,
                secondVariants = listOf(
                    OrientedStructureAsset(HorizontalRotation.NONE, second)
                ),
                placement = AssemblagePlacement.Search(0.30),
                seed = 5,
                maxSolidPairEvaluations = 255,
            )
        )

        val rejected = assertIs<AssemblageOutcome.Rejected>(outcome)
        assertEquals(
            "SEARCH_WORK_BUDGET_EXCEEDED required=256 limit=255",
            rejected.reason,
        )
    }

    @Test
    fun `a tall vertical graft gains a doorway and connecting floor in its seam band`() {
        val first = filledAsset("test:first", "minecraft:stone", Extent3i(6, 4, 3))
        val second = filledAsset("test:second", "minecraft:deepslate", Extent3i(6, 4, 3))
        val result = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageRequest(
                    first = first,
                    secondVariants = listOf(
                        OrientedStructureAsset(HorizontalRotation.NONE, second)
                    ),
                    placement = AssemblagePlacement.Fixed(
                        HorizontalRotation.NONE,
                        Vec3i(2, 0, 0),
                    ),
                    seed = 71,
                )
            )
        ).result

        val doorway = result.scene.cells.filterValues {
            it.selected?.provenance == SceneProvenance.LocalCoherence(LocalCoherenceRole.DOORWAY)
        }
        val floor = result.scene.cells.filterValues {
            it.selected?.provenance == SceneProvenance.LocalCoherence(LocalCoherenceRole.FLOOR_CONNECTION)
        }

        assertEquals(
            setOf(
                Vec3i(3, 1, 1), Vec3i(4, 1, 1),
                Vec3i(3, 2, 1), Vec3i(4, 2, 1),
            ),
            doorway.keys,
        )
        assertTrue(doorway.values.all {
            it.state == BlockStateSpec("minecraft:air") &&
                it.selected?.kind == SceneContributionKind.INFERRED_AIR
        })
        assertEquals(
            (2..5).map { x -> Vec3i(x, 0, 1) }.toSet(),
            floor.keys,
        )
        assertEquals(
            LocalCoherenceReport(
                doorwayCells = 4,
                floorConnectionCells = 4,
                supportCells = 0,
            ),
            result.report.localCoherence,
        )
    }

    @Test
    fun `a floating graft drops supports from the ends of its connecting floor`() {
        val first = floatingAsset("test:first", "minecraft:stone")
        val second = floatingAsset("test:second", "minecraft:deepslate")
        val result = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageRequest(
                    first = first,
                    secondVariants = listOf(
                        OrientedStructureAsset(HorizontalRotation.NONE, second)
                    ),
                    placement = AssemblagePlacement.Fixed(
                        HorizontalRotation.NONE,
                        Vec3i(2, 0, 0),
                    ),
                    seed = 72,
                )
            )
        ).result

        val supports = result.scene.cells.filterValues {
            it.selected?.provenance == SceneProvenance.LocalCoherence(LocalCoherenceRole.SUPPORT)
        }

        assertEquals(
            buildSet {
                for (x in listOf(2, 5)) for (y in 0..2) add(Vec3i(x, y, 1))
            },
            supports.keys,
        )
        assertTrue(supports.values.all {
            it.selected?.kind == SceneContributionKind.INFERRED_BLOCK
        })
    }

    @Test
    fun `a horizontal graft gains a local connecting floor without a doorway`() {
        val first = filledAsset("test:first", "minecraft:stone", Extent3i(3, 6, 3))
        val second = filledAsset("test:second", "minecraft:deepslate", Extent3i(3, 6, 3))
        val result = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageRequest(
                    first = first,
                    secondVariants = listOf(
                        OrientedStructureAsset(HorizontalRotation.NONE, second)
                    ),
                    placement = AssemblagePlacement.Fixed(
                        HorizontalRotation.NONE,
                        Vec3i(0, 2, 0),
                    ),
                    seed = 73,
                )
            )
        ).result

        val floor = result.scene.cells.filterValues {
            it.selected?.provenance == SceneProvenance.LocalCoherence(LocalCoherenceRole.FLOOR_CONNECTION)
        }
        assertEquals(
            buildSet {
                for (z in 0..2) for (x in 0..2) add(Vec3i(x, 3, z))
            },
            floor.keys,
        )
        assertEquals(0, result.report.localCoherence.doorwayCells)
        assertEquals(9, result.report.localCoherence.floorConnectionCells)
    }

    @Test
    fun `a sparse collision still opens a doorway through the visible seam wall`() {
        val first = filledAsset("test:first", "minecraft:stone", Extent3i(6, 4, 3))
        val second = perforatedAsset("test:second", "minecraft:deepslate")
        val result = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageRequest(
                    first = first,
                    secondVariants = listOf(
                        OrientedStructureAsset(HorizontalRotation.NONE, second)
                    ),
                    placement = AssemblagePlacement.Fixed(
                        HorizontalRotation.NONE,
                        Vec3i(2, 0, 0),
                    ),
                    seed = 74,
                )
            )
        ).result

        assertEquals(
            setOf(
                Vec3i(3, 1, 1), Vec3i(4, 1, 1),
                Vec3i(3, 2, 1), Vec3i(4, 2, 1),
            ),
            result.scene.cells.filterValues {
                it.selected?.provenance == SceneProvenance.LocalCoherence(LocalCoherenceRole.DOORWAY)
            }.keys,
        )
    }

    private fun linearAsset(id: String, states: List<String>): StructureAsset = StructureAsset(
        id = AssetId(id),
        size = Extent3i(states.size, 1, 1),
        palettes = listOf(
            AuthoredPalette(
                states.mapIndexed { index, state ->
                    Vec3i(index, 0, 0) to AuthoredCell.Block(BlockStateSpec(state))
                }.toMap()
            )
        ),
        tags = emptySet(),
    )

    private fun filledAsset(
        id: String,
        state: String,
        size: Extent3i,
    ): StructureAsset = StructureAsset(
        id = AssetId(id),
        size = size,
        palettes = listOf(
            AuthoredPalette(
                buildMap {
                    for (z in 0 until size.z) for (y in 0 until size.y) for (x in 0 until size.x) {
                        put(Vec3i(x, y, z), AuthoredCell.Block(BlockStateSpec(state)))
                    }
                }
            )
        ),
        tags = emptySet(),
    )

    private fun floatingAsset(id: String, state: String): StructureAsset {
        val size = Extent3i(6, 6, 3)
        return StructureAsset(
            id = AssetId(id),
            size = size,
            palettes = listOf(
                AuthoredPalette(
                    buildMap {
                        for (z in 0 until size.z) {
                            for (y in 3 until size.y) {
                                for (x in 0 until size.x) {
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
    }

    private fun perforatedAsset(id: String, state: String): StructureAsset {
        val size = Extent3i(6, 4, 3)
        val holes = setOf(Vec3i(1, 2, 1), Vec3i(2, 1, 1))
        return StructureAsset(
            id = AssetId(id),
            size = size,
            palettes = listOf(
                AuthoredPalette(
                    buildMap {
                        for (z in 0 until size.z) {
                            for (y in 0 until size.y) {
                                for (x in 0 until size.x) {
                                    val position = Vec3i(x, y, z)
                                    if (position !in holes) {
                                        put(position, AuthoredCell.Block(BlockStateSpec(state)))
                                    }
                                }
                            }
                        }
                    }
                )
            ),
            tags = emptySet(),
        )
    }
}
