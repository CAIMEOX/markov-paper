package com.caimeo.markovpaper.fabric

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.Vec3i
import com.caimeo.markovpaper.minecraft.GridPlacement
import com.caimeo.markovpaper.minecraft.PreviewCell
import com.caimeo.markovpaper.minecraft.PreviewTarget
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.Logger
import java.util.UUID

/** Native packets and world mutations are confined to the server thread. */
internal class FabricPreviewTarget(
    private val server: MinecraftServer,
    private val playerId: UUID,
    private val dimension: ResourceKey<Level>,
    private val placement: GridPlacement,
    private val tickets: FabricChunkTickets,
    private val logger: Logger,
) : PreviewTarget {
    private val palette = HashMap<BlockStateSpec, BlockState>()
    private var chunk: ChunkPos? = null
    private var lease: FabricChunkTickets.Lease? = null
    private val viewer get() = server.playerList.getPlayer(playerId)?.takeIf { it.level().dimension() == dimension }
    override val viewerAvailable get() = viewer != null

    init { require(!placement.modelZIsUp) }

    override fun validate(size: Extent3i) {
        val world = world()
        require(placement.originY >= world.minY && placement.originY.toLong() + size.y - 1 <= world.maxY) {
            "Generation exceeds world Y ${world.minY}..${world.maxY}"
        }
        Math.addExact(placement.originX, size.x - 1)
        Math.addExact(placement.originZ, size.z - 1)
        require(kotlin.math.abs(placement.originX.toLong()) < 30_000_000 &&
            kotlin.math.abs(placement.originZ.toLong()) < 30_000_000 &&
            placement.originX.toLong() + size.x <= 30_000_000 && placement.originZ.toLong() + size.z <= 30_000_000) {
            "Generation exceeds Minecraft horizontal coordinate limits"
        }
    }

    override fun show(cells: List<PreviewCell>) {
        val player = viewer ?: return
        val world = world()
        val updates = LinkedHashMap<BlockPos, BlockState>()
        for (cell in cells) {
            val position = requireNotNull(position(cell.position, world)) { "Preview cell is outside the world: ${cell.position}" }
            updates[position] = cell.state?.let { state -> palette.getOrPut(state) {
                BlockStateParser.parseForBlock(server.registryAccess().lookupOrThrow(Registries.BLOCK), state.canonical, false).blockState()
            } } ?: actualIfLoaded(world, position) ?: continue
        }
        send(player, world, updates)
    }

    override fun restore(positions: List<Vec3i>): Int {
        val player = viewer ?: return positions.size
        val world = world()
        val updates = LinkedHashMap<BlockPos, BlockState>()
        for (local in positions) {
            val position = position(local, world) ?: continue
            actualIfLoaded(world, position)?.let { updates[position] = it }
        }
        send(player, world, updates)
        for (position in updates.keys) {
            world.getBlockEntity(position)?.updatePacket?.let(player.connection::send)
        }
        return positions.size
    }

    override fun commit(cells: List<PreviewCell>, synchronous: Boolean): Int {
        val world = world()
        var written = 0
        for (cell in cells) {
            val position = requireNotNull(position(cell.position, world)) { "Commit cell is outside the world: ${cell.position}" }
            val state = cell.state
            if (state != null) {
                if (!synchronous && !chunkReady(world, ChunkPos(Math.floorDiv(position.x, 16), Math.floorDiv(position.z, 16)))) break
                val block = palette.getOrPut(state) {
                    BlockStateParser.parseForBlock(server.registryAccess().lookupOrThrow(Registries.BLOCK), state.canonical, false).blockState()
                }
                if (world.getBlockState(position) != block) {
                    check(world.setBlock(position, block, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)) {
                        "World rejected block write at $position"
                    }
                }
            }
            written++
        }
        return written
    }

    private fun chunkReady(world: ServerLevel, position: ChunkPos): Boolean {
        if (chunk != position) {
            lease?.close()
            lease = null
            chunk = null
            lease = tickets.acquire(world, position)
            chunk = position
        }
        val request = requireNotNull(lease)
        if (!request.ready.isDone) return false
        try {
            request.ready.join()
            check(world.chunkSource.getChunkNow(position.x, position.z) != null) { "Chunk load failed at $position" }
        } catch (exception: Exception) {
            request.close()
            lease = null
            chunk = null
            throw exception
        }
        return true
    }

    private fun actualIfLoaded(world: ServerLevel, position: BlockPos): BlockState? =
        world.chunkSource.getChunkNow(Math.floorDiv(position.x, 16), Math.floorDiv(position.z, 16))?.getBlockState(position)

    private fun send(player: ServerPlayer, world: ServerLevel, updates: Map<BlockPos, BlockState>) {
        FabricBlockUpdates.packets(updates, world.palettedContainerFactory()).forEach(player.connection::send)
    }

    private fun position(local: Vec3i, world: ServerLevel): BlockPos? {
        val x = placement.originX.toLong() + local.x
        val y = placement.originY.toLong() + local.y
        val z = placement.originZ.toLong() + local.z
        if (x !in -29_999_999L..29_999_999L || z !in -29_999_999L..29_999_999L || y < world.minY || y > world.maxY) return null
        return BlockPos(x.toInt(), y.toInt(), z.toInt())
    }

    private fun world() = requireNotNull(server.getLevel(dimension)) { "Preview dimension is unavailable" }
    override fun notify(message: String) { server.playerList.getPlayer(playerId)?.sendSystemMessage(Component.literal(message)) }
    override fun failure(operation: String, exception: Exception) { logger.error("{} failed for {} in {}", operation, playerId, dimension, exception) }
    override fun close() { lease?.close(); lease = null; chunk = null; palette.clear() }
}
