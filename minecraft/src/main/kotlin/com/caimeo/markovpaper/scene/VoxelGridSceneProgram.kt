package com.caimeo.markovpaper.scene

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.SceneCell
import com.caimeo.markovpaper.assemblage.SceneContribution
import com.caimeo.markovpaper.assemblage.SceneContributionKind
import com.caimeo.markovpaper.assemblage.SceneProvenance
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.Vec3i
import com.caimeo.markovpaper.engine.CellChange
import com.caimeo.markovpaper.engine.RewriteNode
import com.caimeo.markovpaper.engine.GridView

class VoxelGridSceneProgram(
    private val model: String,
    private val seed: Long,
    private val grid: GridView,
    private val node: RewriteNode,
    private val symbols: List<Char>,
    private val palette: Map<Byte, BlockStateSpec>,
    private val modelZIsUp: Boolean,
    private val changesPerAdvance: Int = 2_048,
) : SceneProgram {
    private val size = if (modelZIsUp) {
        Extent3i(grid.sizeX, grid.sizeZ, grid.sizeY)
    } else {
        Extent3i(grid.sizeX, grid.sizeY, grid.sizeZ)
    }
    private val cells = LinkedHashMap<Vec3i, SceneCell>(
        grid.sizeX * grid.sizeY * grid.sizeZ
    )
    private var pendingChanges = emptyList<CellChange>()
    private var pendingIndex = 0

    override val initial: SceneSnapshot

    override val current: SceneSnapshot
        get() = snapshot()

    init {
        require(model.isNotBlank()) { "Scene program model cannot be blank" }
        require(symbols.isNotEmpty()) { "Scene program needs at least one symbol" }
        require(changesPerAdvance > 0)
        for (z in 0 until grid.sizeZ) {
            for (y in 0 until grid.sizeY) {
                for (x in 0 until grid.sizeX) {
                    cells[scenePosition(x, y, z)] = sceneCell(x, y, z, grid[x, y, z])
                }
            }
        }
        initial = snapshot()
    }

    override fun advance(): SceneProgramDelta? {
        if (pendingIndex >= pendingChanges.size) {
            val delta = node.advance() ?: return null
            pendingChanges = delta.changes
            pendingIndex = 0
            if (pendingChanges.isEmpty()) return SceneProgramDelta(model, emptyList())
        }
        val end = minOf(pendingIndex + changesPerAdvance, pendingChanges.size)
        val changes = LinkedHashMap<Vec3i, SceneProgramChange>()
        for (index in pendingIndex until end) {
            val change = pendingChanges[index]
            val position = scenePosition(change.x, change.y, change.z)
            val before = cells[position]?.state
            val afterCell = sceneCell(change.x, change.y, change.z, change.after)
            cells[position] = afterCell
            val earlier = changes[position]
            changes[position] = SceneProgramChange(
                position = position,
                before = earlier?.before ?: before,
                after = afterCell.state,
            )
        }
        pendingIndex = end
        if (pendingIndex >= pendingChanges.size) {
            pendingChanges = emptyList()
            pendingIndex = 0
        }
        return SceneProgramDelta(
            phase = model,
            changes = changes.values.filter { it.before != it.after },
        )
    }

    private fun sceneCell(x: Int, y: Int, z: Int, value: Byte): SceneCell {
        val symbol = symbols.getOrNull(value.toInt())
            ?: error("Model '$model' produced unmapped value $value")
        val state = requireNotNull(palette[value]) {
            "Model '$model' has no BlockState for value $value"
        }
        val contribution = SceneContribution(
            provenance = SceneProvenance.Procedural(
                model = model,
                seed = seed,
                symbol = symbol,
                sourceCell = Vec3i(x, y, z),
            ),
            kind = if (state.canonical.substringBefore('[') in AIR_BLOCKS) {
                SceneContributionKind.PROCEDURAL_AIR
            } else {
                SceneContributionKind.PROCEDURAL_BLOCK
            },
            state = state,
        )
        return SceneCell(state, contribution, listOf(contribution))
    }

    private fun scenePosition(x: Int, y: Int, z: Int): Vec3i =
        if (modelZIsUp) Vec3i(x, z, y) else Vec3i(x, y, z)

    private fun snapshot(): SceneSnapshot = SceneSnapshot(size, cells.toMap())

    private companion object {
        val AIR_BLOCKS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
    }
}
