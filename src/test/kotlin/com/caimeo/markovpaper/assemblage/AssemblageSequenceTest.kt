package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssemblageSequenceTest {
    @Test
    fun `each added Structure Asset produces a replayable trace frame`() {
        val first = linearAsset("test:first", "minecraft:stone")
        val second = linearAsset("test:second", "minecraft:deepslate")
        val third = linearAsset("test:third", "minecraft:bricks")

        val outcome = ArchitecturalAssemblage.compose(
            AssemblageSequenceRequest(
                first = first,
                additions = listOf(addition(second), addition(third)),
                targetSolidOverlap = 0.5,
                seed = 42,
            )
        )

        val trace = assertIs<AssemblageSequenceOutcome.Generated>(outcome).trace
        assertEquals(
            listOf(first.id, second.id, third.id),
            trace.frames.map { assertIs<TraceSource.Authored>(it.introducedSource).asset },
        )
        assertEquals(trace.frames.last().scene, trace.finalScene)
        assertEquals(
            setOf(trace.finalScene.size),
            trace.frames.map { it.scene.size }.toSet(),
        )
        assertEquals(
            trace.frames.first().scene.cells.keys,
            trace.finalScene.cells.filterValues { cell ->
                cell.contributions.any { (it.provenance as? SceneProvenance.Authored)?.instance == SourceInstanceId("source-0") }
            }.keys,
        )
    }

    @Test
    fun `later additions preserve every source instance and original Asset id`() {
        val first = linearAsset("test:first", "minecraft:stone")
        val second = linearAsset("test:second", "minecraft:deepslate")
        val third = linearAsset("test:third", "minecraft:bricks")

        val trace = assertIs<AssemblageSequenceOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageSequenceRequest(
                    first = first,
                    additions = listOf(addition(second), addition(third)),
                    targetSolidOverlap = 0.5,
                    seed = 42,
                )
            )
        ).trace

        val contributions = trace.finalScene.cells.values
            .flatMap(SceneCell::contributions)
        assertEquals(
            setOf(SourceInstanceId("source-0"), SourceInstanceId("source-1"), SourceInstanceId("source-2")),
            contributions.map { (it.provenance as? SceneProvenance.Authored)?.instance }.toSet(),
        )
        assertEquals(
            setOf(first.id, second.id, third.id),
            contributions.map { (it.provenance as? SceneProvenance.Authored)?.asset }.toSet(),
        )
    }

    @Test
    fun `the first addition keeps the existing two-source seed contract`() {
        val first = linearAsset("test:first", "minecraft:stone")
        val second = linearAsset("test:second", "minecraft:deepslate")
        val seed = 42L

        val sequence = assertIs<AssemblageSequenceOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageSequenceRequest(
                    first = first,
                    additions = listOf(addition(second)),
                    targetSolidOverlap = 0.5,
                    seed = seed,
                )
            )
        ).trace
        val pair = assertIs<AssemblageOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageRequest(
                    first = first,
                    secondVariants = addition(second).variants,
                    placement = AssemblagePlacement.Search(0.5),
                    seed = seed,
                )
            )
        ).result

        assertEquals(pair.report, sequence.frames[1].placement)
        assertEquals(
            pair.scene.cells.mapValues { it.value.state },
            sequence.finalScene.cells.mapValues { it.value.state },
        )
    }

    @Test
    fun `the solid pair budget covers the complete sequence`() {
        val first = linearAsset("test:first", "minecraft:stone")
        val second = linearAsset("test:second", "minecraft:deepslate")
        val third = linearAsset("test:third", "minecraft:bricks")

        val rejected = assertIs<AssemblageSequenceOutcome.Rejected>(
            ArchitecturalAssemblage.compose(
                AssemblageSequenceRequest(
                    first = first,
                    additions = listOf(addition(second), addition(third)),
                    targetSolidOverlap = 0.5,
                    seed = 42,
                    maxSolidPairEvaluations = 9,
                )
            )
        )

        assertEquals(1, rejected.additionIndex)
        assertEquals("SEARCH_WORK_BUDGET_EXCEEDED required=6 limit=5", rejected.reason)
    }

    @Test
    fun `trace placement origins stay aligned with scenes after later expansion`() {
        val first = linearAsset("test:first", "minecraft:stone")
        val second = linearAsset("test:second", "minecraft:deepslate")
        val third = linearAsset("test:third", "minecraft:bricks")
        val trace = assertIs<AssemblageSequenceOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageSequenceRequest(
                    first = first,
                    additions = listOf(addition(second), addition(third)),
                    targetSolidOverlap = 0.5,
                    seed = 42,
                )
            )
        ).trace

        for (frame in trace.frames.drop(1)) {
            val introducedOrigin = requireNotNull(frame.placement).secondOrigin
            assertTrue(
                trace.finalScene.cells[introducedOrigin]?.contributions?.any { contribution ->
                    val source = contribution.provenance as? SceneProvenance.Authored
                    source?.instance == frame.introducedInstance && source?.sourceCell == Vec3i(0, 0, 0)
                } == true,
                "${frame.introducedInstance} origin must address its source cell in final space",
            )
        }
    }

    @Test
    fun `procedural Scene can seed a sequence of authored additions`() {
        val procedural = proceduralScene("apartemazements", seed = 42)
        val village = linearAsset("test:village", "minecraft:oak_planks")

        val trace = assertIs<AssemblageSequenceOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                SceneAssemblageSequenceRequest(
                    first = procedural,
                    firstSource = TraceSource.Procedural("apartemazements", 42),
                    additions = listOf(addition(village)),
                    targetSolidOverlap = 0.5,
                    seed = 42,
                )
            )
        ).trace

        assertEquals(
            TraceSource.Procedural("apartemazements", 42),
            trace.frames.first().introducedSource,
        )
        assertTrue(trace.finalScene.cells.values.any { cell ->
            cell.contributions.any { it.provenance is SceneProvenance.Procedural }
        })
        assertTrue(trace.finalScene.cells.values.any { village.id in it.sources })
    }

    @Test
    fun `sampled search grafts a real-scale asset without exhaustive solid pairs`() {
        val procedural = filledProceduralScene(
            model = "large-wfc",
            seed = 81,
            size = Extent3i(10, 10, 10),
        )
        val village = filledAsset(
            id = "test:village",
            state = "minecraft:oak_planks",
            size = Extent3i(8, 8, 8),
        )

        val request = SceneAssemblageSequenceRequest(
            first = procedural,
            firstSource = TraceSource.Procedural("large-wfc", 81),
            additions = listOf(
                AssemblageAddition(
                    HorizontalRotation.entries.map { rotation ->
                        OrientedStructureAsset(rotation, village)
                    }
                )
            ),
            placement = AssemblagePlacement.SampledSearch(
                targetSolidOverlap = 0.30,
                maxCandidateOffsets = 128,
            ),
            seed = 81,
            maxSolidPairEvaluations = 1_000_000,
        )

        val generated = assertIs<AssemblageSequenceOutcome.Generated>(
            ArchitecturalAssemblage.compose(request)
        )
        val repeated = assertIs<AssemblageSequenceOutcome.Generated>(
            ArchitecturalAssemblage.compose(request)
        )
        val report = generated.trace.frames[1].placement!!
        val exactCollisions = generated.trace.finalScene.cells.values.count { cell ->
            cell.contributions.any { contribution ->
                contribution.provenance is SceneProvenance.Procedural &&
                    contribution.kind.isSolid
            } && cell.contributions.any { contribution ->
                (contribution.provenance as? SceneProvenance.Authored)?.asset == village.id && contribution.kind.isSolid
            }
        }

        assertTrue(report.solidCollisionCount > 0)
        assertEquals(report.solidCollisionCount, exactCollisions)
        assertEquals(
            report,
            repeated.trace.frames[1].placement,
        )
    }

    private fun addition(asset: StructureAsset): AssemblageAddition = AssemblageAddition(
        variants = listOf(OrientedStructureAsset(HorizontalRotation.NONE, asset))
    )

    private fun linearAsset(id: String, state: String): StructureAsset = StructureAsset(
        id = AssetId(id),
        size = Extent3i(2, 1, 1),
        palettes = listOf(
            AuthoredPalette(
                mapOf(
                    Vec3i(0, 0, 0) to AuthoredCell.Block(BlockStateSpec(state)),
                    Vec3i(1, 0, 0) to AuthoredCell.Block(BlockStateSpec(state)),
                )
            )
        ),
        tags = emptySet(),
    )

    private fun proceduralScene(model: String, seed: Long): SceneSnapshot {
        val size = Extent3i(2, 1, 1)
        return SceneSnapshot(
            size = size,
            cells = (0 until size.x).associate { x ->
                val position = Vec3i(x, 0, 0)
                val contribution = SceneContribution(
                    provenance = SceneProvenance.Procedural(model, seed, 'X', position),
                    kind = SceneContributionKind.PROCEDURAL_BLOCK,
                    state = BlockStateSpec("minecraft:stone"),
                )
                position to SceneCell(
                    state = contribution.state,
                    selected = contribution,
                    contributions = listOf(contribution),
                )
            },
        )
    }

    private fun filledProceduralScene(
        model: String,
        seed: Long,
        size: Extent3i,
    ): SceneSnapshot = SceneSnapshot(
        size = size,
        cells = buildMap {
            for (z in 0 until size.z) for (y in 0 until size.y) for (x in 0 until size.x) {
                val position = Vec3i(x, y, z)
                val contribution = SceneContribution(
                    provenance = SceneProvenance.Procedural(model, seed, 'X', position),
                    kind = SceneContributionKind.PROCEDURAL_BLOCK,
                    state = BlockStateSpec("minecraft:stone"),
                )
                put(position, SceneCell(contribution.state, contribution, listOf(contribution)))
            }
        },
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
}
