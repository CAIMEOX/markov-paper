package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.AssemblageTrace
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.Vec3i
import com.caimeo.markovpaper.engine.GridView
import com.caimeo.markovpaper.engine.RewriteNode
import com.caimeo.markovpaper.scene.HybridSceneProgram
import com.caimeo.markovpaper.scene.SceneProgram

internal data class PreviewCell(val position: Vec3i, val state: BlockStateSpec?)
internal data class PreviewFrame(val cells: Sequence<PreviewCell>, val cellCount: Int, val phase: String? = null)
internal data class PreviewResult(val size: Extent3i, val cells: Sequence<PreviewCell>, val details: List<String> = emptyList())

/** Local Minecraft-Y-up data only. Implementations never access Bukkit while advancing. */
internal interface PreviewSource {
    val initialSize: Extent3i
    fun initialCells(): Sequence<PreviewCell>
    fun advance(): PreviewFrame?
    fun finish(): PreviewResult
}

internal class VoxelPreviewSource(
    private val grid: GridView,
    private val node: RewriteNode,
    private val palette: Map<Byte, BlockStateSpec>,
    private val modelZIsUp: Boolean,
) : PreviewSource {
    override val initialSize = if (modelZIsUp) Extent3i(grid.sizeX, grid.sizeZ, grid.sizeY)
        else Extent3i(grid.sizeX, grid.sizeY, grid.sizeZ)

    private fun cell(x: Int, y: Int, z: Int, value: Byte) = PreviewCell(
        if (modelZIsUp) Vec3i(x, z, y) else Vec3i(x, y, z),
        requireNotNull(palette[value]) { "No preview block for state $value" },
    )

    override fun initialCells(): Sequence<PreviewCell> = gridCells(grid, palette, modelZIsUp)

    override fun advance(): PreviewFrame? {
        val delta = node.advance() ?: return null
        return PreviewFrame(delta.changes.asSequence().map { cell(it.x, it.y, it.z, it.after) }, delta.changes.size)
    }

    override fun finish() = PreviewResult(initialSize, initialCells())
}

// A final-volume iterator retains the grid, not the completed solver/source and its history.
private fun gridCells(grid: GridView, palette: Map<Byte, BlockStateSpec>, modelZIsUp: Boolean): Sequence<PreviewCell> = sequence {
    for (z in 0 until grid.sizeZ) for (y in 0 until grid.sizeY) for (x in 0 until grid.sizeX) {
        yield(PreviewCell(
            if (modelZIsUp) Vec3i(x, z, y) else Vec3i(x, y, z),
            requireNotNull(palette[grid[x, y, z]]) { "No preview block for state ${grid[x, y, z]}" },
        ))
    }
}

internal class StaticPreviewSource(private val scene: SceneSnapshot) : PreviewSource {
    override val initialSize = scene.size
    override fun initialCells() = scene.previewCells()
    override fun advance(): PreviewFrame? = null
    override fun finish() = PreviewResult(scene.size, scene.previewCells())
}

internal class TracePreviewSource(trace: AssemblageTrace, changesPerAdvance: Int) : PreviewSource {
    private val plan = sceneTraceAnimationPlan(trace)
    private val playback = SceneTracePlayback(plan, changesPerAdvance)
    override val initialSize = plan.finalScene.size
    override fun initialCells() = plan.initial.previewCells()
    override fun advance(): PreviewFrame? = when (val event = playback.advance()) {
        is SceneTracePlaybackEvent.Complete -> null
        is SceneTracePlaybackEvent.Changes -> PreviewFrame(
            event.changes.asSequence().map { PreviewCell(it.position, it.after) },
            event.changes.size,
            if (event.stageStarted) "${event.stageIndex + 1}/${plan.stages.size}: ${event.stage.introducedSource.label}" else null,
        )
    }
    override fun finish() = PreviewResult(plan.finalScene.size, plan.finalScene.previewCells())
}

internal class ProgramPreviewSource(private val program: SceneProgram) : PreviewSource {
    override val initialSize = program.initial.size
    override fun initialCells() = program.initial.previewCells()
    override fun advance(): PreviewFrame? {
        val delta = program.advance() ?: return null
        return PreviewFrame(delta.changes.asSequence().map { PreviewCell(it.position, it.after) }, delta.changes.size, delta.phase)
    }
    override fun finish(): PreviewResult {
        val scene = program.current
        return PreviewResult(scene.size, scene.previewCells(), (program as? HybridSceneProgram)?.assemblageTrace.details())
    }
}

private fun SceneSnapshot.previewCells(): Sequence<PreviewCell> = cells.asSequence().map { (position, cell) ->
    PreviewCell(position, cell.state)
}

internal fun AssemblageTrace?.details(): List<String> = this?.frames?.drop(1)?.mapIndexedNotNull { index, frame ->
    frame.placement?.let { report ->
        "- graft ${index + 1}: ${frame.introducedSource.label} " +
            "rotation=${report.secondRotation} " +
            "offset=${report.secondOffset.x},${report.secondOffset.y},${report.secondOffset.z} " +
            "collisions=${report.solidCollisionCount} " +
            "seam=${report.localCoherence.doorwayCells}/" +
            "${report.localCoherence.floorConnectionCells}/${report.localCoherence.supportCells}"
    }
}.orEmpty()
