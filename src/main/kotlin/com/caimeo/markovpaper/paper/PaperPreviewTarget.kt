package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.minecraft.GridPlacement
import com.caimeo.markovpaper.minecraft.PreviewTarget
import com.caimeo.markovpaper.minecraft.PreviewCell

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.Vec3i
import io.papermc.paper.math.Position
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** All Bukkit access stays here on the server thread; solver workers exchange plain data only. */
internal class PaperPreviewTarget(
    private val plugin: Plugin,
    private val playerId: UUID,
    private val worldId: UUID,
    private val placement: GridPlacement,
) : PreviewTarget {
    private val palette = HashMap<BlockStateSpec, BlockData>()
    private var loadingChunk: CompletableFuture<Chunk>? = null
    private val viewer get() = plugin.server.getPlayer(playerId)?.takeIf { it.isOnline && it.world.uid == worldId }
    override val viewerAvailable: Boolean get() = viewer != null

    init { require(!placement.modelZIsUp) { "Preview sources already use Minecraft Y-up coordinates" } }

    override fun validate(size: Extent3i) {
        val world = world()
        require(placement.originY >= world.minHeight && placement.originY.toLong() + size.y <= world.maxHeight) {
            "Generation Y ${placement.originY}..${placement.originY.toLong() + size.y - 1} is outside " +
                "${world.minHeight}..${world.maxHeight - 1}"
        }
        Math.addExact(placement.originX, size.x - 1)
        Math.addExact(placement.originZ, size.z - 1)
    }

    override fun show(cells: List<PreviewCell>) {
        val player = viewer ?: return
        val world = world()
        val frame = HashMap<Position, BlockData>(cells.size)
        for (cell in cells) {
            val position = position(cell.position, world)
            val state = cell.state?.let(::blockData) ?: actualIfLoaded(world, position) ?: continue
            frame[position] = state
        }
        if (frame.isNotEmpty()) player.sendMultiBlockChange(frame)
    }

    override fun restore(positions: List<Vec3i>): Int {
        val player = viewer ?: return positions.size
        val world = world()
        val frame = HashMap<Position, BlockData>(positions.size)
        for (local in positions) {
            // A failed send may include invalid positions that were tracked before validation.
            val position = positionOrNull(local, world) ?: continue
            actualIfLoaded(world, position)?.let { frame[position] = it }
        }
        // Unloaded chunks will be sent from real world data when the client next loads them.
        if (frame.isNotEmpty()) player.sendMultiBlockChange(frame)
        return positions.size
    }

    override fun commit(cells: List<PreviewCell>, synchronous: Boolean): Int {
        val world = world()
        var written = 0
        for (cell in cells) {
            val position = position(cell.position, world)
            val state = cell.state
            if (state != null) {
                if (!synchronous && !chunkReady(world, position)) break
                world.getBlockAt(position.blockX(), position.blockY(), position.blockZ())
                    .setBlockData(blockData(state), false)
            }
            written++
        }
        return written
    }

    private fun chunkReady(world: World, position: Position): Boolean {
        val x = Math.floorDiv(position.blockX(), 16)
        val z = Math.floorDiv(position.blockZ(), 16)
        val pending = loadingChunk
        if (pending != null) {
            if (!pending.isDone) return false
            loadingChunk = null
            pending.join() // Already completed; propagate failures so the retained batch can retry.
        }
        if (world.isChunkLoaded(x, z)) return true
        loadingChunk = world.getChunkAtAsync(x, z, true)
        return false
    }

    private fun actualIfLoaded(world: World, position: Position): BlockData? =
        if (world.isChunkLoaded(Math.floorDiv(position.blockX(), 16), Math.floorDiv(position.blockZ(), 16)))
            world.getBlockAt(position.blockX(), position.blockY(), position.blockZ()).blockData
        else null

    private fun position(local: Vec3i, world: World): Position = requireNotNull(positionOrNull(local, world)) {
        "Generation position $local at $placement is outside the world"
    }

    private fun positionOrNull(local: Vec3i, world: World): Position? {
        val x = placement.originX.toLong() + local.x
        val y = placement.originY.toLong() + local.y
        val z = placement.originZ.toLong() + local.z
        if (x !in Int.MIN_VALUE..Int.MAX_VALUE || z !in Int.MIN_VALUE..Int.MAX_VALUE ||
            y < world.minHeight || y >= world.maxHeight) return null
        return Position.block(x.toInt(), y.toInt(), z.toInt())
    }

    private fun world(): World = requireNotNull(plugin.server.getWorld(worldId)) { "Preview world is unavailable" }
    private fun blockData(state: BlockStateSpec): BlockData = palette.getOrPut(state) { plugin.server.createBlockData(state.canonical) }
    override fun notify(message: String) { plugin.server.getPlayer(playerId)?.sendMessage(message) }
    override fun failure(operation: String, exception: Exception) {
        plugin.logger.log(Level.SEVERE, "$operation failed for player=$playerId world=$worldId", exception)
    }
    override fun close() { loadingChunk = null; palette.clear() }
}
