package com.caimeo.markovpaper.minecraft

import com.caimeo.markovpaper.assemblage.ArchitecturalAssemblage
import com.caimeo.markovpaper.assemblage.AssemblageAddition
import com.caimeo.markovpaper.assemblage.AssemblageSequenceOutcome
import com.caimeo.markovpaper.assemblage.AssemblageSequenceRequest
import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.AuthoredPalette
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.HorizontalRotation
import com.caimeo.markovpaper.assemblage.OrientedStructureAsset
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.TraceSource
import com.caimeo.markovpaper.assemblage.Vec3i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SceneTraceAnimationPlanTest {
    @Test
    fun `replaying trace stages produces exactly the final Scene`() {
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

        val plan = sceneTraceAnimationPlan(trace)
        val replay = plan.initial.cells.mapValuesTo(LinkedHashMap()) { it.value.state }
        for (stage in plan.stages) {
            for (change in stage.changes) {
                if (change.after == null) replay.remove(change.position)
                else replay[change.position] = change.after
            }
        }

        assertEquals(listOf(second.id, third.id), plan.stages.map { assertIs<TraceSource.Authored>(it.introducedSource).asset })
        assertEquals(
            trace.finalScene.cells.mapValues { it.value.state },
            replay,
        )
    }

    @Test
    fun `playback exposes the final Scene only after every animation batch`() {
        val first = linearAsset("test:first", "minecraft:stone")
        val second = linearAsset("test:second", "minecraft:deepslate")
        val trace = assertIs<AssemblageSequenceOutcome.Generated>(
            ArchitecturalAssemblage.compose(
                AssemblageSequenceRequest(
                    first = first,
                    additions = listOf(addition(second)),
                    targetSolidOverlap = 0.5,
                    seed = 42,
                )
            )
        ).trace
        val playback = SceneTracePlayback(sceneTraceAnimationPlan(trace), changesPerTick = 1)

        val events = buildList {
            while (true) {
                val event = playback.advance()
                add(event)
                if (event is SceneTracePlaybackEvent.Complete) break
            }
        }

        assertTrue(events.dropLast(1).all { it is SceneTracePlaybackEvent.Changes })
        assertEquals(
            trace.finalScene,
            assertIs<SceneTracePlaybackEvent.Complete>(events.last()).scene,
        )
    }

    private fun addition(asset: StructureAsset): AssemblageAddition = AssemblageAddition(
        listOf(OrientedStructureAsset(HorizontalRotation.NONE, asset))
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
}
