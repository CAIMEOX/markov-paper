package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.AssetReference
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.CatalogSnapshot
import com.caimeo.markovpaper.assemblage.ControlKind
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.StructureLoadResult
import com.caimeo.markovpaper.workbench.AssetDescriptor
import com.caimeo.markovpaper.workbench.AssetDescriptors
import com.caimeo.markovpaper.workbench.DebugPlot
import com.caimeo.markovpaper.workbench.buildDebugWorldLayout
import io.papermc.paper.math.Position
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

class AssetDebugWorldManager(
    private val plugin: Plugin,
    private val catalog: CatalogSnapshot,
) {
    private val revisionKey = NamespacedKey(plugin, "asset_debug_revision")
    private val ownerKey = NamespacedKey(plugin, "asset_debug_owner")
    private val journey = DebugWorldJourney()
    private var job: BuildJob? = null

    fun build(sender: CommandSender) {
        if (job != null) {
            sender.sendMessage("Asset debug world build is already running.")
            return
        }
        val references = catalog.selectReferences()
        val world = try {
            debugWorld()
        } catch (exception: Exception) {
            sender.sendMessage(exception.message ?: "Could not open asset debug world")
            return
        }
        val encodedStamp = world.persistentDataContainer.get(
            revisionKey,
            PersistentDataType.STRING,
        )
        val stamp = DebugBuildStamp.parse(encodedStamp)
        if (stamp?.isCurrentComplete(DEBUG_SCHEMA, catalog.revision.value, references.size) == true) {
            sender.sendMessage(
                "Asset debug world is already complete for revision ${stamp.revision} " +
                    "(${stamp.placed}/${stamp.expected} assets)."
            )
            return
        }
        if (stamp?.isCurrent(DEBUG_SCHEMA, catalog.revision.value, references.size) == true &&
            !stamp.canRetryInPlace()
        ) {
            sender.sendMessage(
                "Asset debug world contains an incomplete materialized layout. " +
                    "Run /mj debugworld rebuild to preserve it as a backup and build a clean world."
            )
            return
        }
        if (encodedStamp != null && stamp?.isCurrent(
                DEBUG_SCHEMA,
                catalog.revision.value,
                references.size,
            ) != true
        ) {
            sender.sendMessage(
                "Asset debug world layout does not match the current catalog. " +
                    "Run /mj debugworld rebuild to preserve it as a backup and build a fresh world."
            )
            return
        }
        world.persistentDataContainer.set(
            revisionKey,
            PersistentDataType.STRING,
            DebugBuildStamp(
                schema = DEBUG_SCHEMA,
                revision = catalog.revision.value,
                expected = references.size,
                placed = 0,
                failed = 0,
            ).encode(),
        )
        world.save()
        job = BuildJob(sender, world, references)
        sender.sendMessage(
            "Started debug world build for ${references.size} Structure Assets."
        )
    }

    fun status(sender: CommandSender) {
        val current = job
        if (current != null) {
            sender.sendMessage(
                "Debug world ${current.phase.name.lowercase()}: " +
                    "described=${current.referenceIndex}/${current.references.size}, " +
                    "placed=${current.plotIndex}/${current.loaded.size}, " +
                    "blocks=${current.blocksPlaced}, failed=${current.failed}."
            )
            return
        }
        val worldDirectory = worldDirectory()
        if (Bukkit.getWorld(DEBUG_WORLD_NAME) == null && !Files.exists(worldDirectory)) {
            sender.sendMessage("Asset debug world has not been built.")
            return
        }
        val world = try {
            debugWorld()
        } catch (exception: Exception) {
            sender.sendMessage(exception.message ?: "Could not open asset debug world")
            return
        }
        val encodedStamp = world.persistentDataContainer.get(
            revisionKey,
            PersistentDataType.STRING,
        )
        val stamp = DebugBuildStamp.parse(encodedStamp)
        val expected = catalog.selectReferences().size
        when {
            stamp == null -> sender.sendMessage(
                "Asset debug world exists but has no current build stamp; use /mj debugworld rebuild."
            )
            stamp.isCurrentComplete(DEBUG_SCHEMA, catalog.revision.value, expected) ->
                sender.sendMessage(
                    "Asset debug world is complete for revision ${stamp.revision}: " +
                        "${stamp.placed}/${stamp.expected} assets."
                )
            stamp.isCurrent(DEBUG_SCHEMA, catalog.revision.value, expected) &&
                !stamp.canRetryInPlace() -> sender.sendMessage(
                    "Asset debug world contains an incomplete materialized layout: " +
                        "${stamp.placed}/${stamp.expected} assets, failed=${stamp.failed}. " +
                        "Use /mj debugworld rebuild."
                )
            stamp.isCurrent(DEBUG_SCHEMA, catalog.revision.value, expected) ->
                sender.sendMessage(
                    "Asset debug world is incomplete for revision ${stamp.revision}: " +
                        "${stamp.placed}/${stamp.expected} assets, failed=${stamp.failed}. " +
                        "Run /mj debugworld build to retry."
                )
            else -> sender.sendMessage(
                "Asset debug world is stale (schema=${stamp.schema}, revision=${stamp.revision}, " +
                    "assets=${stamp.expected}); use /mj debugworld rebuild."
            )
        }
    }

    fun rebuild(sender: CommandSender) {
        if (job != null) {
            sender.sendMessage("Asset debug world build is already running.")
            return
        }
        val worldDirectory = worldDirectory()
        if (!Files.exists(worldDirectory)) {
            sender.sendMessage("No previous asset debug world exists; starting a fresh build.")
            build(sender)
            return
        }
        val world = try {
            debugWorld()
        } catch (exception: Exception) {
            sender.sendMessage(exception.message ?: "Could not open asset debug world")
            return
        }
        if (world.players.isNotEmpty()) {
            sender.sendMessage(
                "Leave $DEBUG_WORLD_NAME before rebuilding it (${world.players.size} player(s) remain)."
            )
            return
        }
        world.save()
        if (!Bukkit.unloadWorld(world, true)) {
            sender.sendMessage("Could not unload $DEBUG_WORLD_NAME; rebuild was not started.")
            return
        }
        val backup = worldDirectory.resolveSibling(
            "$DEBUG_WORLD_NAME-backup-${System.currentTimeMillis()}"
        )
        try {
            Files.move(worldDirectory, backup)
        } catch (exception: Exception) {
            sender.sendMessage(
                "Could not back up $DEBUG_WORLD_NAME: " +
                    (exception.message ?: exception.javaClass.simpleName)
            )
            return
        }
        sender.sendMessage("Previous debug world moved to ${backup.fileName}; starting a fresh build.")
        build(sender)
    }

    fun teleport(player: Player) {
        val world = try {
            debugWorld()
        } catch (exception: Exception) {
            player.sendMessage(exception.message ?: "Could not open asset debug world")
            return
        }
        val source = player.location
        val returnPoint = if (source.world.uid != world.uid) {
            DebugReturnPoint(
                worldId = source.world.uid,
                x = source.x,
                y = source.y,
                z = source.z,
                yaw = source.yaw,
                pitch = source.pitch,
            )
        } else {
            null
        }
        if (player.teleport(Location(world, SPAWN_X + 0.5, BASE_Y + 2.0, SPAWN_Z + 0.5))) {
            if (returnPoint != null) journey.remember(player.uniqueId, returnPoint)
            player.sendMessage("Teleported to $DEBUG_WORLD_NAME; use /mj debugworld exit to return.")
        } else {
            player.sendMessage("Could not teleport to $DEBUG_WORLD_NAME.")
        }
    }

    fun exit(player: Player) {
        if (player.world.name != DEBUG_WORLD_NAME) {
            player.sendMessage("You are not in $DEBUG_WORLD_NAME.")
            return
        }
        val remembered = journey.take(player.uniqueId)
        val rememberedWorld = remembered?.let { plugin.server.getWorld(it.worldId) }
        val destination = if (remembered != null && rememberedWorld != null &&
            rememberedWorld.name != DEBUG_WORLD_NAME
        ) {
            Location(
                rememberedWorld,
                remembered.x,
                remembered.y,
                remembered.z,
                remembered.yaw,
                remembered.pitch,
            )
        } else {
            primaryWorld().spawnLocation
        }
        if (player.teleport(destination)) {
            player.sendMessage("Returned to ${destination.world.name}.")
        } else {
            if (remembered != null) journey.remember(player.uniqueId, remembered)
            player.sendMessage("Could not leave $DEBUG_WORLD_NAME.")
        }
    }

    fun tick() {
        val current = job ?: return
        try {
            when (current.phase) {
                BuildPhase.DESCRIBE -> describe(current)
                BuildPhase.PLACE -> place(current)
            }
        } catch (exception: Exception) {
            plugin.logger.severe("Asset debug world build failed: ${exception.message}")
            current.sender.sendMessage(
                "Asset debug world build failed: ${exception.message ?: exception.javaClass.simpleName}"
            )
            job = null
        }
    }

    fun close() {
        job = null
        journey.clear()
    }

    private fun describe(job: BuildJob) {
        repeat(DESCRIPTORS_PER_TICK) {
            val reference = job.references.getOrNull(job.referenceIndex) ?: run {
                val stamp = DebugBuildStamp(
                    schema = DEBUG_SCHEMA,
                    revision = catalog.revision.value,
                    expected = job.references.size,
                    placed = 0,
                    failed = job.failed,
                )
                if (!stamp.allowsPlacementOf(job.loaded.size)) {
                    job.world.persistentDataContainer.set(
                        revisionKey,
                        PersistentDataType.STRING,
                        stamp.encode(),
                    )
                    job.world.save()
                    job.sender.sendMessage(
                        "Debug world description stopped before placement: " +
                            "${job.loaded.size}/${job.references.size} assets loaded, " +
                            "${job.failed} failed. Run /mj debugworld build to retry."
                    )
                    this.job = null
                    return
                }
                job.layout = buildDebugWorldLayout(job.loaded.map { it.descriptor })
                job.phase = BuildPhase.PLACE
                job.sender.sendMessage(
                    "Debug world descriptors ready: ${job.loaded.size} assets, " +
                        "${job.failed} failed. Starting incremental placement."
                )
                return
            }
            job.referenceIndex++
            when (val loaded = catalog.load(reference.id)) {
                is StructureLoadResult.Loaded -> job.loaded += LoadedDebugAsset(
                    reference,
                    loaded.asset,
                    AssetDescriptors.describe(loaded.asset),
                )
                else -> job.failed++
            }
        }
    }

    private fun place(job: BuildJob) {
        var remaining = WORK_UNITS_PER_TICK
        while (remaining > 0) {
            val item = job.loaded.getOrNull(job.plotIndex) ?: run {
                complete(job)
                return
            }
            val plot = requireNotNull(job.layout).plots[job.plotIndex]
            if (job.currentCells == null) {
                preparePlot(job.world, plot)
                spawnLabels(job.world, item, plot)
                job.world.getBlockAt(plot.origin.x, plot.origin.y - 1, plot.origin.z)
                    .setType(Material.GOLD_BLOCK, false)
                job.currentCells = item.asset.palettes.first().cells.entries.iterator()
                remaining--
            }
            val cells = requireNotNull(job.currentCells)
            while (remaining > 0 && cells.hasNext()) {
                val (position, authored) = cells.next()
                remaining--
                val state = when (authored) {
                    is AuthoredCell.Block -> authored.state
                    is AuthoredCell.Control -> authored.state
                    AuthoredCell.AuthoredAir, AuthoredCell.StructureVoid -> null
                }
                if (state != null) {
                    job.world.getBlockAt(
                        plot.origin.x + position.x,
                        plot.origin.y + position.y,
                        plot.origin.z + position.z,
                    ).setBlockData(Bukkit.createBlockData(state.canonical), false)
                    job.blocksPlaced++
                }
            }
            if (!cells.hasNext()) {
                job.currentCells = null
                job.plotIndex++
                if (job.plotIndex % 25 == 0) {
                    job.sender.sendMessage(
                        "Debug world progress: ${job.plotIndex}/${job.loaded.size} assets."
                    )
                }
            }
        }
    }

    private fun complete(job: BuildJob) {
        val stamp = DebugBuildStamp(
            schema = DEBUG_SCHEMA,
            revision = catalog.revision.value,
            expected = job.references.size,
            placed = job.plotIndex,
            failed = job.failed,
        )
        job.world.persistentDataContainer.set(
            revisionKey,
            PersistentDataType.STRING,
            stamp.encode(),
        )
        job.world.save()
        if (stamp.isCurrentComplete(DEBUG_SCHEMA, catalog.revision.value, job.references.size)) {
            job.sender.sendMessage(
                "Asset debug world complete: ${job.loaded.size} assets, " +
                    "${job.blocksPlaced} blocks, ${job.failed} failed."
            )
        } else {
            job.sender.sendMessage(
                "Asset debug world build is incomplete: ${job.loaded.size}/${job.references.size} " +
                    "assets placed, ${job.failed} failed. Run /mj debugworld build to retry."
            )
        }
        this.job = null
    }

    private fun preparePlot(world: World, plot: DebugPlot) {
        val maximumX = plot.origin.x + plot.descriptor.size.x - 1
        val maximumZ = plot.origin.z + plot.descriptor.size.z - 1
        val plotTag = plotTag(plot.index)
        for (chunkX in Math.floorDiv(plot.origin.x, 16)..Math.floorDiv(maximumX, 16)) {
            for (chunkZ in Math.floorDiv(plot.origin.z, 16)..Math.floorDiv(maximumZ, 16)) {
                world.getChunkAt(chunkX, chunkZ).entities
                    .filter { plotTag in it.scoreboardTags }
                    .forEach { it.remove() }
            }
        }
    }

    private fun spawnLabels(world: World, item: LoadedDebugAsset, plot: DebugPlot) {
        val jigsaws = item.descriptor.controls.filter { it.kind == ControlKind.JIGSAW }
        val family = item.asset.tags.firstOrNull { it.value.startsWith("family:") }
            ?.value?.substringAfter(':') ?: "uncategorized"
        val dimensions = item.asset.tags
            .map { it.value }
            .filter { it.startsWith("dimension:") }
            .map { it.substringAfter(':') }
            .sorted()
            .joinToString("/")
        spawnText(
            world,
            Location(
                world,
                plot.origin.x + item.descriptor.size.x / 2.0,
                plot.origin.y + item.descriptor.size.y + 2.0,
                plot.origin.z + item.descriptor.size.z / 2.0,
            ),
            Component.text(
                "${plot.index + 1}. $family [$dimensions]\n" +
                    "${item.reference.shortest}\n" +
                    "${item.descriptor.size.x}x${item.descriptor.size.y}x${item.descriptor.size.z} " +
                    "solid=${item.descriptor.solidCells} jigsaw=${jigsaws.size}"
            ),
            lineWidth = 240,
            plotIndex = plot.index,
        )
        jigsaws.forEachIndexed { index, marker ->
            spawnText(
                world,
                Location(
                    world,
                    plot.origin.x + marker.position.x + 0.5,
                    plot.origin.y + marker.position.y + 1.25,
                    plot.origin.z + marker.position.z + 0.5,
                ),
                Component.text("J$index ${marker.orientation ?: "?"}"),
                lineWidth = 100,
                plotIndex = plot.index,
            )
        }
    }

    private fun spawnText(
        world: World,
        location: Location,
        text: Component,
        lineWidth: Int,
        plotIndex: Int,
    ) {
        world.spawn(location, TextDisplay::class.java) { display ->
            display.text(text)
            display.billboard = Display.Billboard.CENTER
            display.isShadowed = true
            display.isSeeThrough = true
            display.lineWidth = lineWidth
            display.viewRange = 2.0f
            display.addScoreboardTag(DEBUG_TAG)
            display.addScoreboardTag(plotTag(plotIndex))
        }
    }

    private fun debugWorld(): World {
        val existing = Bukkit.getWorld(DEBUG_WORLD_NAME)
        if (existing != null) {
            val folder = existing.worldPath
            val owned = java.nio.file.Files.isRegularFile(
                folder.resolve(DebugWorldOwnership.MARKER_FILE)
            ) || existing.persistentDataContainer.has(ownerKey, PersistentDataType.BYTE) ||
                existing.persistentDataContainer.has(revisionKey, PersistentDataType.STRING)
            require(owned) {
                "World '$DEBUG_WORLD_NAME' exists but is not owned by Markov Paper"
            }
            DebugWorldOwnership.markOwned(folder)
            existing.persistentDataContainer.set(ownerKey, PersistentDataType.BYTE, 1.toByte())
            return existing
        }
        val worldDirectory = worldDirectory()
        DebugWorldOwnership.requireOwnedOrAbsent(worldDirectory)
        val world = requireNotNull(
            WorldCreator(DEBUG_WORLD_NAME)
                .environment(World.Environment.NORMAL)
                .generator(VoidChunkGenerator)
                .generateStructures(false)
                .forcedSpawnPosition(Position.block(SPAWN_X, BASE_Y + 1, SPAWN_Z), 0f, 0f)
                .createWorld()
        ) { "Could not create $DEBUG_WORLD_NAME" }
        DebugWorldOwnership.markOwned(world.worldPath)
        world.setGameRule(GameRules.SPAWN_MOBS, false)
        world.setGameRule(GameRules.ADVANCE_TIME, false)
        world.setGameRule(GameRules.FALL_DAMAGE, false)
        world.time = 6_000
        world.setStorm(false)
        world.isThundering = false
        world.isVoidDamageEnabled = false
        world.persistentDataContainer.set(ownerKey, PersistentDataType.BYTE, 1.toByte())
        for (x in SPAWN_X - 4..SPAWN_X + 4) for (z in SPAWN_Z - 4..SPAWN_Z + 4) {
            world.getBlockAt(x, BASE_Y, z).setType(Material.SMOOTH_STONE, false)
        }
        return world
    }

    private fun worldDirectory(): Path {
        Bukkit.getWorld(DEBUG_WORLD_NAME)?.let { return it.worldPath }
        return DebugWorldOwnership.dimensionDirectory(primaryWorld().worldPath, DEBUG_WORLD_NAME)
    }

    private fun primaryWorld(): World = requireNotNull(
        plugin.server.worlds.firstOrNull { it.key == OVERWORLD_KEY }
            ?: plugin.server.worlds.firstOrNull {
                it.environment == World.Environment.NORMAL && it.name != DEBUG_WORLD_NAME
            }
    ) { "Could not locate the primary overworld" }

    private fun plotTag(index: Int): String = "$PLOT_TAG_PREFIX$index"

    private data class LoadedDebugAsset(
        val reference: AssetReference,
        val asset: StructureAsset,
        val descriptor: AssetDescriptor,
    )

    private data class BuildJob(
        val sender: CommandSender,
        val world: World,
        val references: List<AssetReference>,
        val loaded: MutableList<LoadedDebugAsset> = ArrayList(),
        var referenceIndex: Int = 0,
        var failed: Int = 0,
        var phase: BuildPhase = BuildPhase.DESCRIBE,
        var layout: com.caimeo.markovpaper.workbench.DebugWorldLayout? = null,
        var plotIndex: Int = 0,
        var currentCells: Iterator<Map.Entry<com.caimeo.markovpaper.assemblage.Vec3i, AuthoredCell>>? = null,
        var blocksPlaced: Long = 0,
    )

    private enum class BuildPhase {
        DESCRIBE,
        PLACE,
    }

    private object VoidChunkGenerator : ChunkGenerator() {
        override fun shouldGenerateNoise(): Boolean = false
        override fun shouldGenerateSurface(): Boolean = false
        override fun shouldGenerateCaves(): Boolean = false
        override fun shouldGenerateDecorations(): Boolean = false
        override fun shouldGenerateMobs(): Boolean = false
        override fun shouldGenerateStructures(): Boolean = false
        override fun canSpawn(world: World, x: Int, z: Int): Boolean = true
        override fun getFixedSpawnLocation(world: World, random: Random): Location =
            Location(world, SPAWN_X + 0.5, BASE_Y + 1.0, SPAWN_Z + 0.5)
    }

    companion object {
        const val DEBUG_WORLD_NAME = "markov-asset-debug"
        private val OVERWORLD_KEY = requireNotNull(NamespacedKey.fromString("minecraft:overworld"))
        private const val DEBUG_TAG = "markov-asset-debug"
        private const val PLOT_TAG_PREFIX = "markov-asset-debug-plot-"
        private const val DEBUG_SCHEMA = 1
        private const val BASE_Y = 64
        private const val SPAWN_X = -20
        private const val SPAWN_Z = -20
        private const val DESCRIPTORS_PER_TICK = 4
        private const val WORK_UNITS_PER_TICK = 2_000
    }
}
