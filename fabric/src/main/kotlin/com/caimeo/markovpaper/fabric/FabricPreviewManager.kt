package com.caimeo.markovpaper.fabric

import com.caimeo.markovpaper.minecraft.BlockPoint
import com.caimeo.markovpaper.minecraft.GenerationMode
import com.caimeo.markovpaper.minecraft.GridPlacement
import com.caimeo.markovpaper.minecraft.ModelCatalog
import com.caimeo.markovpaper.minecraft.PreparedMinecraftModel
import com.caimeo.markovpaper.minecraft.PreviewLifecycle
import com.caimeo.markovpaper.minecraft.PreviewPacing
import com.caimeo.markovpaper.minecraft.VoxelPreviewSource
import com.caimeo.markovpaper.minecraft.WorldBounds
import com.caimeo.markovpaper.minecraft.validateGenerationShape
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.Level
import org.slf4j.Logger
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.sqrt

internal class FabricPreviewManager(
    private val server: MinecraftServer,
    directory: Path,
    ticketType: TicketType,
    private val logger: Logger,
) : AutoCloseable {
    val models = ModelCatalog(directory.resolve("models")).apply { installBundled() }
    private val tickets = FabricChunkTickets(ticketType)
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "markov-fabric-worker").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val sessions = HashMap<UUID, PreviewLifecycle>()
    private data class Anchor(val dimension: ResourceKey<Level>, val corner: BlockPoint)
    private data class BoundsDisplay(val dimension: ResourceKey<Level>, val bounds: WorldBounds, var ticks: Int = 200)
    private val anchors = HashMap<UUID, Anchor>()
    private val bounds = HashMap<UUID, BoundsDisplay>()

    fun start(player: ServerPlayer, name: String, size: Int?, seed: Long, speed: Int?, height: Int?, mode: GenerationMode) {
        if (!stop(player.uuid)) return
        require(models.contains(name)) { "Unknown model '$name'. Use /mj models." }
        val profile = models.profile(name)
        val prepared = models.prepare(name, size ?: profile.defaultSize, height)
        val placement = placement(player, prepared)
        val runtime = prepared.execution.create(seed)
        sessions[player.uuid] = PreviewLifecycle(
            VoxelPreviewSource(runtime.grid, runtime.node, prepared.palette, prepared.modelZIsUp),
            FabricPreviewTarget(server, player.uuid, player.level().dimension(), placement.copy(modelZIsUp = false), tickets, logger),
            workers, mode, name, PreviewPacing(advances = speed ?: profile.rewrites),
        )
        player.sendSystemMessage(Component.literal("Started ${mode.name.lowercase()} $name, seed=$seed, from ${placement.originX},${placement.originY},${placement.originZ}."))
    }

    fun setPosition(player: ServerPlayer, point: BlockPos) {
        anchors[player.uuid] = Anchor(player.level().dimension(), BlockPoint(point.x, point.y, point.z))
        bounds.remove(player.uuid)
        player.sendSystemMessage(Component.literal("Generation bottom corner set to ${point.x},${point.y},${point.z}."))
    }

    fun clearPosition(player: ServerPlayer) {
        anchors.remove(player.uuid)
        bounds.remove(player.uuid)
        player.sendSystemMessage(Component.literal("Custom generation corner cleared."))
    }

    fun showBounds(player: ServerPlayer, name: String, size: Int?, height: Int?) {
        val prepared = models.prepare(name, size ?: models.profile(name).defaultSize, height)
        val placement = placement(player, prepared)
        val output = prepared.size
        bounds[player.uuid] = BoundsDisplay(player.level().dimension(), WorldBounds.fromModel(placement, output.x, output.y, output.z))
        player.sendSystemMessage(Component.literal("Showing $name bounds for 10 seconds."))
    }

    private fun placement(player: ServerPlayer, model: PreparedMinecraftModel): GridPlacement {
        val size = model.size
        val anchor = anchors[player.uuid]?.takeIf { it.dimension == player.level().dimension() }
        val placement = if (anchor != null) GridPlacement.atBottomCorner(anchor.corner, model.modelZIsUp) else {
            val direction = player.getLookAngle()
            val length = sqrt(direction.x * direction.x + direction.z * direction.z)
            val depth = if (model.modelZIsUp) size.y else size.z
            val distance = maxOf(size.x, depth) + 6.0
            val dx = if (length < 1.0e-6) 0.0 else direction.x / length
            val dz = if (length < 1.0e-6) 1.0 else direction.z / length
            GridPlacement.centered(BlockPoint(floor(player.x + dx * distance).toInt(), player.blockY,
                floor(player.z + dz * distance).toInt()), size.x, size.y, size.z, model.modelZIsUp)
        }
        validateGenerationShape(placement, size.x, size.y, size.z, player.level().minY, player.level().maxY + 1)
        return placement
    }

    fun stop(playerId: UUID): Boolean {
        bounds.remove(playerId)
        val done = sessions[playerId]?.requestStop() ?: true
        if (done) sessions.remove(playerId)
        workers.purge()
        return done
    }

    fun disconnect(playerId: UUID) {
        if (sessions[playerId]?.detach() != false) sessions.remove(playerId)
        anchors.remove(playerId)
        bounds.remove(playerId)
        workers.purge()
    }

    fun tick() {
        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            try { if (session.tick()) iterator.remove() }
            catch (exception: Exception) { logger.error("Preview lifecycle failed; retaining pending work", exception) }
        }
        anchors.entries.removeIf { (id, anchor) -> server.playerList.getPlayer(id)?.level()?.dimension() != anchor.dimension }
        val boxes = bounds.iterator()
        while (boxes.hasNext()) {
            val (id, display) = boxes.next()
            val player = server.playerList.getPlayer(id)
            if (player == null || player.level().dimension() != display.dimension || display.ticks-- <= 0) {
                boxes.remove()
                continue
            }
            if (display.ticks % 10 == 0) for (point in display.bounds.edgePoints(64)) {
                player.level().sendParticles(player, DustParticleOptions(0x00FFFF, 1.0f), true, false,
                    point.x + 0.5, point.y + 0.5, point.z + 0.5, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    override fun close() {
        try {
            for (session in sessions.values) {
                try { session.close() } catch (exception: Exception) { logger.error("Preview shutdown failed", exception) }
            }
        } finally {
            workers.shutdownNow()
            sessions.clear()
            anchors.clear()
            bounds.clear()
            tickets.close()
        }
    }
}
