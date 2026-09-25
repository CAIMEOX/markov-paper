package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.minecraft.BlockPoint
import com.caimeo.markovpaper.minecraft.GridPlacement
import com.caimeo.markovpaper.minecraft.WorldBounds
import com.caimeo.markovpaper.minecraft.PreviewLifecycle
import com.caimeo.markovpaper.minecraft.PreviewPacing
import com.caimeo.markovpaper.minecraft.PreviewSource
import com.caimeo.markovpaper.minecraft.VoxelPreviewSource
import com.caimeo.markovpaper.minecraft.StaticPreviewSource
import com.caimeo.markovpaper.minecraft.TracePreviewSource
import com.caimeo.markovpaper.minecraft.ProgramPreviewSource
import com.caimeo.markovpaper.minecraft.ModelCatalog
import com.caimeo.markovpaper.minecraft.GenerationMode
import com.caimeo.markovpaper.minecraft.validateGenerationShape
import com.caimeo.markovpaper.minecraft.details

import com.caimeo.markovpaper.assemblage.AssemblageTrace
import com.caimeo.markovpaper.assemblage.AssemblageAddition
import com.caimeo.markovpaper.assemblage.ControlKind
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.TraceSource
import com.caimeo.markovpaper.scene.HybridSceneProgram
import com.caimeo.markovpaper.scene.MacroAssemblageProgram
import com.caimeo.markovpaper.scene.SceneProgramDelta
import com.caimeo.markovpaper.scene.VoxelGridSceneProgram
import com.caimeo.markovpaper.workbench.AssetGallery
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.math.floor

class PreviewManager(
    private val plugin: Plugin,
    modelDirectory: Path,
) {
    private val sessions = HashMap<UUID, PreviewLifecycle>()
    private val workers = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "markov-preview-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val anchors = HashMap<UUID, GenerationAnchor>()
    private val boundsDisplays = HashMap<UUID, BoundsDisplay>()
    private val hybridTraces = ArrayList<HeadlessHybridTrace>()
    private val workbenchDisplays = HashMap<UUID, WorkbenchDisplay>()
    private val models = ModelCatalog(modelDirectory).apply { installBundled() }

    fun availableModels(): List<String> = models.names()

    fun availableHybridModels(): List<String> = models.names().filter {
        runCatching { models.profile(it).hybrid }.getOrDefault(false)
    }

    fun hasModel(name: String): Boolean = models.contains(name)

    fun hasHybridModel(name: String): Boolean = hasModel(name) && models.profile(name).hybrid

    fun defaultSize(name: String): Int = models.profile(name).defaultSize

    fun defaultSpeed(name: String): Int = models.profile(name).rewrites

    fun start(
        player: Player,
        modelName: String,
        requestedSize: Int,
        seed: Long,
        stepsPerTick: Int,
        mode: GenerationMode,
        requestedHeight: Int?,
    ) {
        if (!stop(player)) return
        boundsDisplays.remove(player.uniqueId)

        val prepared = models.prepare(modelName, requestedSize, requestedHeight)
        val shape = prepared.size
        val placement = placementFor(
            player = player,
            sizeX = shape.x,
            sizeY = shape.y,
            sizeZ = shape.z,
            modelZIsUp = prepared.modelZIsUp,
        )
        validateGenerationShape(
            placement = placement,
            sizeX = shape.x,
            sizeY = shape.y,
            sizeZ = shape.z,
            worldMinHeight = player.world.minHeight,
            worldMaxHeight = player.world.maxHeight,
        )
        val runtime = prepared.execution.create(seed)

        sessions[player.uniqueId] = lifecycle(
            player, placement.copy(modelZIsUp = false),
            VoxelPreviewSource(runtime.grid, runtime.node, prepared.palette, prepared.modelZIsUp),
            mode, pacing = PreviewPacing(advances = stepsPerTick),
        )
        player.sendMessage(
            "Started ${mode.name.lowercase()} $modelName " +
                "${runtime.grid.sizeX}x${runtime.grid.sizeY}x" +
                "${runtime.grid.sizeZ} Markov animation " +
                "from bottom corner ${placement.originX},${placement.originY}," +
                "${placement.originZ}, with seed $seed " +
                "and up to $stepsPerTick rewrites/compute batch."
        )
    }

    fun setPosition(player: Player, corner: BlockPoint) {
        anchors[player.uniqueId] = GenerationAnchor(player.world.uid, corner)
        boundsDisplays.remove(player.uniqueId)
    }

    fun previewScene(player: Player, label: String, scene: SceneSnapshot) {
        if (!stop(player)) return
        boundsDisplays.remove(player.uniqueId)
        val placement = placementFor(
            player = player,
            sizeX = scene.size.x,
            sizeY = scene.size.y,
            sizeZ = scene.size.z,
            modelZIsUp = false,
        )
        sessions[player.uniqueId] = lifecycle(player, placement, StaticPreviewSource(scene), label = label)
        player.sendMessage(
            "Previewing $label ${scene.size.x}x${scene.size.y}x${scene.size.z} " +
                "from bottom corner ${placement.originX},${placement.originY},${placement.originZ}."
        )
    }

    fun previewWorkbench(
        player: Player,
        label: String,
        gallery: AssetGallery,
        portsVisible: Boolean,
    ) {
        if (!stop(player)) return
        boundsDisplays.remove(player.uniqueId)
        val placement = placementFor(
            player = player,
            sizeX = gallery.size.x,
            sizeY = gallery.size.y,
            sizeZ = gallery.size.z,
            modelZIsUp = false,
        )
        sessions[player.uniqueId] = lifecycle(player, placement, StaticPreviewSource(gallery.scene), label = label)
        if (portsVisible) {
            workbenchDisplays[player.uniqueId] = WorkbenchDisplay(
                worldId = player.world.uid,
                gallery = gallery,
                placement = placement,
            )
            spawnWorkbenchOverlay(player, gallery, placement)
        }
        player.sendMessage(
            "Previewing $label ${gallery.size.x}x${gallery.size.y}x${gallery.size.z}."
        )
    }

    fun previewTrace(
        player: Player,
        label: String,
        trace: AssemblageTrace,
        changesPerTick: Int,
        mode: GenerationMode,
    ) {
        if (!stop(player)) return
        boundsDisplays.remove(player.uniqueId)
        val scene = trace.finalScene
        val placement = placementFor(
            player = player,
            sizeX = scene.size.x,
            sizeY = scene.size.y,
            sizeZ = scene.size.z,
            modelZIsUp = false,
        )
        sessions[player.uniqueId] = lifecycle(
            player, placement, TracePreviewSource(trace, changesPerTick), mode, label,
            PreviewPacing(displayBlocks = minOf(changesPerTick, 512)),
        )
        player.sendMessage(
            "Animating ${mode.name.lowercase()} $label with ${trace.frames.size} " +
                "Structure Assets, seed=${trace.seed}, " +
                "size=${scene.size.x}x${scene.size.y}x${scene.size.z}, " +
                "up to ${minOf(changesPerTick, 512)} block changes/tick."
        )
    }

    fun startHybrid(
        player: Player,
        modelName: String,
        requestedSize: Int,
        seed: Long,
        additions: List<AssemblageAddition>,
        mode: GenerationMode,
        maxSolidPairEvaluations: Long,
        changesPerAdvance: Int,
    ) {
        if (!stop(player)) return
        boundsDisplays.remove(player.uniqueId)
        val program = createHybridProgram(
            modelName,
            requestedSize,
            seed,
            additions,
            maxSolidPairEvaluations,
            changesPerAdvance,
        )
        val placement = placementFor(
            player = player,
            sizeX = program.initial.size.x,
            sizeY = program.initial.size.y,
            sizeZ = program.initial.size.z,
            modelZIsUp = false,
        )
        sessions[player.uniqueId] = lifecycle(
            player, placement, ProgramPreviewSource(program), mode, "Hybrid",
            PreviewPacing(displayBlocks = minOf(changesPerAdvance, 512)),
        )
        player.sendMessage(
            "Started hybrid ${mode.name.lowercase()} with $modelName, " +
                "${additions.size} authored additions, and seed=$seed."
        )
    }

    fun startMacroAssemblage(
        player: Player,
        program: MacroAssemblageProgram,
        changesPerTick: Int,
    ): Boolean {
        if (!stop(player)) return false
        boundsDisplays.remove(player.uniqueId)
        val scene = program.initial
        val placement = placementFor(
            player = player,
            sizeX = scene.size.x,
            sizeY = scene.size.y,
            sizeZ = scene.size.z,
            modelZIsUp = false,
        )
        sessions[player.uniqueId] = lifecycle(
            player, placement, ProgramPreviewSource(program), label = "Macro assemblage",
            pacing = PreviewPacing(displayBlocks = minOf(changesPerTick, 512)),
        )
        val plan = program.plan
        player.sendMessage(
            "Started macro preview with ${plan.instances.size} Structure Asset instances, " +
                "${plan.candidateEvaluations} candidates/${plan.solidContactProbes} " +
                "solid probes, " +
                "size=${scene.size.x}x${scene.size.y}x${scene.size.z}."
        )
        return true
    }

    fun startHybridTrace(
        sender: CommandSender,
        modelName: String,
        requestedSize: Int,
        seed: Long,
        additions: List<AssemblageAddition>,
        maxSolidPairEvaluations: Long,
        changesPerAdvance: Int,
    ) {
        require(hybridTraces.size < MAX_CONCURRENT_HYBRID_TRACES) {
            "Too many hybrid traces are already running"
        }
        require(hybridTraces.none { it.sender.name == sender.name }) {
            "${sender.name} already has a hybrid trace running"
        }
        val program = createHybridProgram(
            modelName,
            requestedSize,
            seed,
            additions,
            maxSolidPairEvaluations,
            changesPerAdvance,
        )
        hybridTraces += HeadlessHybridTrace(sender, program)
        sender.sendMessage(
            "Started bounded hybrid trace with $modelName, ${additions.size} " +
                "authored additions, and seed=$seed."
        )
    }

    private fun createHybridProgram(
        modelName: String,
        requestedSize: Int,
        seed: Long,
        additions: List<AssemblageAddition>,
        maxSolidPairEvaluations: Long,
        changesPerAdvance: Int,
    ): HybridSceneProgram {
        require(hasHybridModel(modelName)) {
            "Model '$modelName' is not enabled for bounded hybrid generation"
        }
        val prepared = models.prepare(modelName, requestedSize, maxCells = HYBRID_MAX_VOXELS)
        val runtime = prepared.execution.create(seed)
        val prefix = VoxelGridSceneProgram(
            model = modelName,
            seed = seed,
            grid = runtime.grid,
            node = runtime.node,
            symbols = runtime.symbols,
            palette = prepared.palette,
            modelZIsUp = prepared.modelZIsUp,
        )
        return HybridSceneProgram(
            prefix = prefix,
            prefixSource = TraceSource.Procedural(modelName, seed),
            additions = additions,
            targetSolidOverlap = 0.30,
            seed = seed,
            maxSolidPairEvaluations = maxSolidPairEvaluations,
            changesPerAdvance = changesPerAdvance,
        )
    }

    fun clearPosition(player: Player): Boolean {
        boundsDisplays.remove(player.uniqueId)
        return anchors.remove(player.uniqueId) != null
    }

    fun showBounds(
        player: Player,
        modelName: String,
        requestedSize: Int,
        requestedHeight: Int?,
    ): WorldBounds {
        val prepared = models.prepare(modelName, requestedSize, requestedHeight)
        val shape = prepared.size
        val placement = placementFor(
            player = player,
            sizeX = shape.x,
            sizeY = shape.y,
            sizeZ = shape.z,
            modelZIsUp = prepared.modelZIsUp,
        )
        validateGenerationShape(
            placement = placement,
            sizeX = shape.x,
            sizeY = shape.y,
            sizeZ = shape.z,
            worldMinHeight = player.world.minHeight,
            worldMaxHeight = player.world.maxHeight,
        )
        val bounds = WorldBounds.fromModel(
            placement = placement,
            sizeX = shape.x,
            sizeY = shape.y,
            sizeZ = shape.z,
        )
        boundsDisplays[player.uniqueId] = BoundsDisplay(player.world.uid, bounds)
        spawnBounds(player, bounds)
        return bounds
    }

    fun stop(player: Player): Boolean {
        val restored = sessions[player.uniqueId]?.requestStop() ?: true
        if (restored) sessions.remove(player.uniqueId)
        cancelTraces(player.uniqueId)
        boundsDisplays.remove(player.uniqueId)
        workbenchDisplays.remove(player.uniqueId)
        return restored
    }

    fun discard(playerId: UUID) {
        if (sessions[playerId]?.detach() != false) sessions.remove(playerId)
        cancelTraces(playerId)
        anchors.remove(playerId)
        boundsDisplays.remove(playerId)
        workbenchDisplays.remove(playerId)
    }

    fun tick() {
        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            try {
                if (session.tick()) iterator.remove()
            } catch (exception: Exception) {
                // Even a broken transport callback must not prevent unrelated sessions from advancing.
                plugin.logger.log(Level.SEVERE, "Preview lifecycle failed; retaining the session for cleanup", exception)
            }
        }

        val traceIterator = hybridTraces.iterator()
        while (traceIterator.hasNext()) {
            val trace = traceIterator.next()
            try {
                val pending = trace.work
                if (pending != null && pending.isDone) {
                    val delta = pending.get()
                    trace.work = null
                    if (delta == null) {
                        sendHybridTraceResult(trace.sender, trace.program)
                        traceIterator.remove()
                        continue
                    } else if (delta.phase != null && delta.phase != trace.lastPhase) {
                        trace.sender.sendMessage("Hybrid phase: ${delta.phase}.")
                        trace.lastPhase = delta.phase
                    }
                }
                if (trace.work == null) {
                    val task = FutureTask { trace.program.advance() }
                    trace.work = task
                    workers.execute(task)
                }
            } catch (exception: Exception) {
                trace.work?.cancel(true)
                traceIterator.remove()
                plugin.logger.log(Level.SEVERE, "Hybrid trace failed", exception)
                trace.sender.sendMessage(
                    "Hybrid trace stopped: ${exception.message ?: exception.javaClass.simpleName}"
                )
            }
        }

        val boundsIterator = boundsDisplays.iterator()
        while (boundsIterator.hasNext()) {
            val (playerId, display) = boundsIterator.next()
            val player = plugin.server.getPlayer(playerId)
            if (
                player == null || !player.isOnline || player.world.uid != display.worldId ||
                display.remainingTicks <= 0
            ) {
                boundsIterator.remove()
                continue
            }
            if (display.remainingTicks % BOUNDS_REFRESH_TICKS == 0) {
                spawnBounds(player, display.bounds)
            }
            display.remainingTicks--
        }

        val workbenchIterator = workbenchDisplays.iterator()
        while (workbenchIterator.hasNext()) {
            val (playerId, display) = workbenchIterator.next()
            val player = plugin.server.getPlayer(playerId)
            if (player == null || !player.isOnline || player.world.uid != display.worldId) {
                workbenchIterator.remove()
                continue
            }
            if (display.ticks++ % WORKBENCH_REFRESH_TICKS == 0) {
                spawnWorkbenchOverlay(player, display.gallery, display.placement)
            }
        }
    }

    fun close() {
        try {
            for (session in sessions.values) {
                try { session.close() } catch (exception: Exception) {
                    plugin.logger.log(Level.SEVERE, "Preview shutdown cleanup failed", exception)
                }
            }
        } finally {
            hybridTraces.forEach { it.work?.cancel(true) }
            workers.shutdownNow()
            sessions.clear()
            hybridTraces.clear()
            anchors.clear()
            boundsDisplays.clear()
            workbenchDisplays.clear()
        }
    }

    private fun cancelTraces(playerId: UUID) {
        hybridTraces.removeIf { trace ->
            (trace.ownerId == playerId).also { if (it) trace.work?.cancel(true) }
        }
        workers.purge()
    }

    private fun lifecycle(
        player: Player,
        placement: GridPlacement,
        source: PreviewSource,
        mode: GenerationMode = GenerationMode.PREVIEW,
        label: String = "Markov",
        pacing: PreviewPacing = PreviewPacing(),
    ) = PreviewLifecycle(source, PaperPreviewTarget(plugin, player.uniqueId, player.world.uid, placement), workers, mode, label, pacing)

    private fun placementFor(
        player: Player,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        modelZIsUp: Boolean,
    ): GridPlacement {
        val anchor = anchors[player.uniqueId]
        if (anchor != null) {
            require(anchor.worldId == player.world.uid) {
                "The configured position belongs to another world; use /mj setpos again"
            }
            return GridPlacement.atBottomCorner(anchor.corner, modelZIsUp)
        }
        return GridPlacement.centered(
            center = automaticCenter(player, sizeX, sizeY, sizeZ, modelZIsUp),
            sizeX = sizeX,
            sizeY = sizeY,
            sizeZ = sizeZ,
            modelZIsUp = modelZIsUp,
        )
    }

    private fun automaticCenter(
        player: Player,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        modelZIsUp: Boolean,
    ): BlockPoint {
        val direction = player.location.direction.setY(0)
        if (direction.lengthSquared() < 1.0e-6) {
            direction.setX(0).setY(0).setZ(1)
        } else {
            direction.normalize()
        }
        val depthSize = if (modelZIsUp) sizeY else sizeZ
        val distance = maxOf(sizeX, depthSize) + 6.0
        return BlockPoint(
            x = floor(player.location.x + direction.x * distance).toInt(),
            y = player.location.blockY,
            z = floor(player.location.z + direction.z * distance).toInt(),
        )
    }

    private fun spawnBounds(
        player: Player,
        bounds: WorldBounds,
        maxSamplesPerEdge: Int = 64,
    ) {
        for (point in bounds.edgePoints(maxSamplesPerEdge)) {
            player.spawnParticle(
                Particle.DUST,
                point.x + 0.5,
                point.y + 0.5,
                point.z + 0.5,
                1,
                BOUNDS_DUST,
            )
        }
    }

    private fun spawnWorkbenchOverlay(
        player: Player,
        gallery: AssetGallery,
        placement: GridPlacement,
    ) {
        for (item in gallery.placements) {
            val origin = placement.worldPosition(item.origin.x, item.origin.y, item.origin.z)
            val itemPlacement = GridPlacement.atBottomCorner(origin, modelZIsUp = false)
            val bounds = WorldBounds.fromModel(
                itemPlacement,
                item.descriptor.size.x,
                item.descriptor.size.y,
                item.descriptor.size.z,
            )
            spawnBounds(player, bounds, WORKBENCH_BOUND_SAMPLES)
            player.spawnParticle(
                Particle.DUST,
                origin.x + 0.5,
                origin.y + 0.5,
                origin.z + 0.5,
                1,
                WORKBENCH_ORIGIN_DUST,
            )
        }
        for (marker in gallery.controls.filter { it.kind == ControlKind.JIGSAW }) {
            val point = placement.worldPosition(marker.position.x, marker.position.y, marker.position.z)
            val direction = marker.orientation?.substringBefore('_')?.let(::orientationVector)
            for (step in 0..3) {
                val distance = step * 0.4
                player.spawnParticle(
                    Particle.DUST,
                    point.x + 0.5 + (direction?.x ?: 0) * distance,
                    point.y + 0.5 + (direction?.y ?: 0) * distance,
                    point.z + 0.5 + (direction?.z ?: 0) * distance,
                    1,
                    WORKBENCH_JIGSAW_DUST,
                )
            }
        }
    }

    private fun orientationVector(name: String): BlockPoint? = when (name) {
        "north" -> BlockPoint(0, 0, -1)
        "south" -> BlockPoint(0, 0, 1)
        "east" -> BlockPoint(1, 0, 0)
        "west" -> BlockPoint(-1, 0, 0)
        "up" -> BlockPoint(0, 1, 0)
        "down" -> BlockPoint(0, -1, 0)
        else -> null
    }

    private data class GenerationAnchor(
        val worldId: UUID,
        val corner: BlockPoint,
    )

    private data class BoundsDisplay(
        val worldId: UUID,
        val bounds: WorldBounds,
        var remainingTicks: Int = BOUNDS_DURATION_TICKS,
    )

    private data class HeadlessHybridTrace(
        val sender: CommandSender,
        val program: HybridSceneProgram,
        var lastPhase: String? = null,
        var work: FutureTask<SceneProgramDelta?>? = null,
    ) {
        val ownerId: UUID? = (sender as? Player)?.uniqueId
    }

    private data class WorkbenchDisplay(
        val worldId: UUID,
        val gallery: AssetGallery,
        val placement: GridPlacement,
        var ticks: Int = 1,
    )

    private fun sendHybridTraceResult(sender: CommandSender, program: HybridSceneProgram) {
        val scene = program.current
        sender.sendMessage("Hybrid trace complete: ${scene.size.x}x${scene.size.y}x${scene.size.z}.")
        program.assemblageTrace.details().forEach(sender::sendMessage)
    }

    companion object {
        private const val BOUNDS_DURATION_TICKS = 200
        private const val BOUNDS_REFRESH_TICKS = 10
        private const val HYBRID_MAX_VOXELS = 20_000L
        private const val MAX_CONCURRENT_HYBRID_TRACES = 4
        private const val WORKBENCH_REFRESH_TICKS = 40
        private const val WORKBENCH_BOUND_SAMPLES = 10
        private val BOUNDS_DUST = Particle.DustOptions(Color.AQUA, 1.0f)
        private val WORKBENCH_ORIGIN_DUST = Particle.DustOptions(Color.YELLOW, 1.4f)
        private val WORKBENCH_JIGSAW_DUST = Particle.DustOptions(Color.FUCHSIA, 1.2f)
    }
}
