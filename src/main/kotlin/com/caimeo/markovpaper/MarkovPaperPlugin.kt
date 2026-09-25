package com.caimeo.markovpaper

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AssetReferenceResolution
import com.caimeo.markovpaper.assemblage.ArchitecturalAssemblage
import com.caimeo.markovpaper.assemblage.AssemblageAddition
import com.caimeo.markovpaper.assemblage.AssemblageOutcome
import com.caimeo.markovpaper.assemblage.AssemblagePlacement
import com.caimeo.markovpaper.assemblage.AssemblageRequest
import com.caimeo.markovpaper.assemblage.AssemblageSequenceOutcome
import com.caimeo.markovpaper.assemblage.AssemblageSequenceRequest
import com.caimeo.markovpaper.assemblage.CatalogSnapshot
import com.caimeo.markovpaper.assemblage.HorizontalRotation
import com.caimeo.markovpaper.assemblage.FrontierAssemblageOutcome
import com.caimeo.markovpaper.assemblage.FrontierAssemblagePlanner
import com.caimeo.markovpaper.assemblage.FrontierAssemblageRequest
import com.caimeo.markovpaper.assemblage.OrientedStructureAsset
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.StructureAssetScenes
import com.caimeo.markovpaper.assemblage.StructureAssetTransforms
import com.caimeo.markovpaper.assemblage.StructureLoadResult
import com.caimeo.markovpaper.assemblage.StructureQuery
import com.caimeo.markovpaper.assemblage.VanillaStructureCatalogs
import com.caimeo.markovpaper.paper.PreviewManager
import com.caimeo.markovpaper.paper.MarkovCommandAccess
import com.caimeo.markovpaper.paper.AssetWorkbenchManager
import com.caimeo.markovpaper.paper.AssetDebugWorldManager
import com.caimeo.markovpaper.minecraft.BlockPoint
import com.caimeo.markovpaper.minecraft.GenerationMode
import com.caimeo.markovpaper.paper.PaperStructureAssetSource
import com.caimeo.markovpaper.paper.PaperAuthoredCellRotation
import com.caimeo.markovpaper.paper.describeAssemblageResult
import com.caimeo.markovpaper.paper.describeStructureAsset
import com.caimeo.markovpaper.paper.parseBlockCoordinate
import com.caimeo.markovpaper.paper.parseAssemblageSequenceInvocation
import com.caimeo.markovpaper.paper.parseHybridInvocation
import com.caimeo.markovpaper.paper.parseWorkbenchGrowthInvocation
import com.caimeo.markovpaper.scene.MacroAssemblageProgram
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level
import kotlin.random.Random

class MarkovPaperPlugin : JavaPlugin(), Listener {
    private lateinit var previews: PreviewManager
    private lateinit var structureCatalog: CatalogSnapshot
    private lateinit var workbench: AssetWorkbenchManager
    private lateinit var debugWorld: AssetDebugWorldManager
    private val orientedStructureAssets = HashMap<AssetId, List<OrientedStructureAsset>>()

    override fun onEnable() {
        previews = PreviewManager(this, dataFolder.toPath().resolve("models"))
        val minecraftVersion = Bukkit.getMinecraftVersion()
        try {
            structureCatalog = VanillaStructureCatalogs.open(
                minecraftVersion = minecraftVersion,
                source = PaperStructureAssetSource(server.structureManager),
            )
        } catch (exception: Exception) {
            logger.log(Level.WARNING, "Structure catalog unavailable for Minecraft $minecraftVersion; XML generation is available", exception)
        }
        if (::structureCatalog.isInitialized) {
            try {
                workbench = AssetWorkbenchManager(structureCatalog, previews)
            } catch (exception: Exception) {
                logger.log(Level.WARNING, "Asset Workbench unavailable", exception)
            }
            try {
                debugWorld = AssetDebugWorldManager(this, structureCatalog)
            } catch (exception: Exception) {
                logger.log(Level.WARNING, "Asset Debug World unavailable", exception)
            }
        }
        server.pluginManager.registerEvents(this, this)
        server.scheduler.runTaskTimer(this, Runnable {
            previews.tick()
            if (::debugWorld.isInitialized) debugWorld.tick()
        }, 1L, 1L)
        logger.info(
            "Markov Paper animation runtime enabled with " +
                "${if (::structureCatalog.isInitialized) structureCatalog.assets.size else 0} indexed vanilla structure assets"
        )
    }

    override fun onDisable() {
        try {
            if (::previews.isInitialized) previews.close()
        } finally {
            if (::debugWorld.isInitialized) debugWorld.close()
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (!MarkovCommandAccess.allows(args, sender::hasPermission)) {
            sender.sendMessage("Missing permission: ${MarkovCommandAccess.permission(args)}")
            return true
        }
        val action = args.firstOrNull()?.lowercase() ?: "preview"
        unavailableFeature(action)?.let { feature ->
            sender.sendMessage("$feature is unavailable. Check the server startup log for details.")
            return true
        }
        if (action == "assets") {
            listStructureAssets(sender, args)
            return true
        }
        if (action == "asset") {
            structureAssetCommand(sender, args)
            return true
        }
        if (action == "assemblage") {
            assemblageCommand(sender, args)
            return true
        }
        if (action == "hybrid") {
            hybridCommand(sender, args)
            return true
        }
        if (action == "workbench") {
            workbenchCommand(sender, args)
            return true
        }
        if (action == "debugworld") {
            debugWorldCommand(sender, args)
            return true
        }

        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("Run this command as a player.")
            return true
        }

        when (action) {
            "preview" -> startGeneration(player, args, GenerationMode.PREVIEW)
            "materialize" -> startGeneration(player, args, GenerationMode.MATERIALIZE)
            "stop" -> {
                if (previews.stop(player)) {
                    player.sendMessage("Markov animation stopped and client blocks restored.")
                }
            }
            "setpos" -> setPosition(player, args)
            "bound" -> showBounds(player, args)
            "clearpos" -> {
                val removed = previews.clearPosition(player)
                player.sendMessage(
                    if (removed) "Custom generation corner cleared."
                    else "No custom generation corner was set."
                )
            }
            "models" -> player.sendMessage(
                "Available Markov models: ${previews.availableModels().joinToString()}"
            )
            else -> player.sendMessage(
                "Usage: /mj preview|materialize [model] [size] [seed] [rewritesPerTick] [height] | " +
                    "/mj setpos [x y z] | /mj bound [model] [size] [height] | /mj clearpos | " +
                    "/mj models | /mj assets [search] | /mj asset inspect|preview <asset-ref> | " +
                    "/mj assemblage inspect|preview <first> <second> [seed] | " +
                    "/mj assemblage trace|animate|materialize " +
                    "[seed] <first> <second> [more...] | " +
                    "/mj hybrid trace|animate|materialize [seed] <model> <size> <assets...> | " +
                    "/mj workbench open|next|prev|pick|ports|add|tray|animate|materialize|grow | " +
                    "/mj debugworld build|rebuild|status|tp|exit | " +
                    "/mj stop"
            )
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): MutableList<String> {
        if (args.isEmpty()) return mutableListOf()
        if (args.size > 1 && unavailableFeature(args[0].lowercase()) != null) return mutableListOf()
        if (args.size >= 3 && !MarkovCommandAccess.allows(args, sender::hasPermission)) return mutableListOf()
        val partial = args.last().lowercase()
        val sequenceOperation = args[0].equals("assemblage", ignoreCase = true) &&
            args.getOrNull(1)?.lowercase() in setOf("trace", "animate", "materialize")
        val sequenceHasSeed = sequenceOperation && args.getOrNull(2)?.toLongOrNull() != null
        val hybridOperation = args[0].equals("hybrid", ignoreCase = true) &&
            args.getOrNull(1)?.lowercase() in setOf("trace", "animate", "materialize")
        val hybridHasSeed = hybridOperation && args.getOrNull(2)?.toLongOrNull() != null
        val hybridAssetStart = if (hybridHasSeed) 6 else 5
        val completingAsset =
            args[0].equals("asset", ignoreCase = true) && args.size == 3 ||
                args[0].equals("assemblage", ignoreCase = true) &&
                args.getOrNull(1)?.lowercase() in setOf("inspect", "preview") &&
                args.size in 3..4 ||
                sequenceOperation && args.size >= (if (sequenceHasSeed) 4 else 3) ||
                hybridOperation && args.size >= hybridAssetStart
        if (completingAsset) {
            return assetReferenceCompletions(partial).toMutableList()
        }
        val choices = when {
            args.size == 1 -> ROOT_COMMANDS.filter { unavailableFeature(it) == null }
            args[0].equals("asset", ignoreCase = true) && args.size == 2 ->
                listOf("inspect", "preview")
            args[0].equals("assemblage", ignoreCase = true) && args.size == 2 ->
                listOf("inspect", "preview", "trace", "animate", "materialize")
            args[0].equals("hybrid", ignoreCase = true) && args.size == 2 ->
                listOf("trace", "animate", "materialize")
            args[0].equals("workbench", ignoreCase = true) && args.size == 2 ->
                listOf(
                    "open", "next", "prev", "back", "pick", "ports", "add",
                    "tray", "remove", "clear", "animate", "materialize",
                    "grow",
                )
            args[0].equals("workbench", ignoreCase = true) &&
                args.getOrNull(1).equals("grow", ignoreCase = true) && args.size == 3 ->
                listOf("20", "50", "100")
            args[0].equals("workbench", ignoreCase = true) &&
                args.getOrNull(1).equals("add", ignoreCase = true) && args.size == 3 ->
                listOf("all", "1", "2", "3", "4", "5", "6")
            args[0].equals("debugworld", ignoreCase = true) && args.size == 2 ->
                listOf("build", "rebuild", "status", "tp", "exit")
            hybridOperation && args.size == (if (hybridHasSeed) 4 else 3) ->
                previews.availableHybridModels()
            hybridOperation && args.size == (if (hybridHasSeed) 5 else 4) -> {
                val modelIndex = if (hybridHasSeed) 3 else 2
                val model = args.getOrNull(modelIndex)?.lowercase()
                if (model != null && previews.hasModel(model)) {
                    listOfNotNull(runCatching { previews.defaultSize(model).toString() }.getOrNull())
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
        return choices.filter {
            it.lowercase().startsWith(partial) &&
                MarkovCommandAccess.allows((args.dropLast(1) + it).toTypedArray(), sender::hasPermission)
        }.toMutableList()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        previews.discard(event.player.uniqueId)
        if (::workbench.isInitialized) workbench.discard(event.player.uniqueId)
    }

    private fun unavailableFeature(action: String): String? = when (action) {
        "assets", "asset", "assemblage", "hybrid" -> if (::structureCatalog.isInitialized) null else "Structure catalog"
        "workbench" -> if (::workbench.isInitialized) null else "Asset Workbench"
        "debugworld" -> if (::debugWorld.isInitialized) null else "Asset Debug World"
        else -> null
    }

    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        previews.discard(event.player.uniqueId)
    }

    private fun startGeneration(
        player: Player,
        args: Array<out String>,
        mode: GenerationMode,
    ) {
        val modelName = args.getOrNull(1)?.lowercase() ?: "maze"
        if (!previews.hasModel(modelName)) {
            player.sendMessage(
                "Unknown model '$modelName'. Available models: " +
                    previews.availableModels().joinToString()
            )
            return
        }

        val seed = args.getOrNull(3)?.toLongOrNull() ?: Random.nextLong()
        val heightToken = args.getOrNull(5)
        val height = heightToken?.toIntOrNull()
        if (heightToken != null && height == null) {
            player.sendMessage("Invalid height '$heightToken'.")
            return
        }
        try {
            val size = args.getOrNull(2)?.toIntOrNull() ?: previews.defaultSize(modelName)
            val stepsPerTick = args.getOrNull(4)?.toIntOrNull()?.coerceIn(1, 64) ?: previews.defaultSpeed(modelName)
            previews.start(player, modelName, size, seed, stepsPerTick, mode, height)
        } catch (exception: Exception) {
            logger.warning("Could not start model '$modelName': ${exception.message}")
            player.sendMessage("Could not load '$modelName': ${exception.message}")
        }
    }

    private fun listStructureAssets(sender: CommandSender, args: Array<out String>) {
        val text = args.drop(1).joinToString(" ").trim().takeIf(String::isNotEmpty)
        val matches = structureCatalog.selectReferences(StructureQuery(text = text))
        sender.sendMessage(
            "Indexed vanilla structure assets: ${structureCatalog.assets.size}; " +
                "matches: ${matches.size}."
        )
        for (asset in matches.take(12)) {
            sender.sendMessage(
                if (asset.shortest == asset.id.toString()) "- ${asset.id}"
                else "- ${asset.shortest} (${asset.id})"
            )
        }
        if (matches.size > 12) sender.sendMessage("- … ${matches.size - 12} more")
    }

    private fun structureAssetCommand(sender: CommandSender, args: Array<out String>) {
        val operation = args.getOrNull(1)?.lowercase()
        val token = args.getOrNull(2)
        if (operation !in setOf("inspect", "preview") || token == null) {
            sender.sendMessage("Usage: /mj asset inspect|preview <asset-ref>")
            return
        }
        val asset = loadStructureAsset(sender, token) ?: return
        when (operation) {
            "inspect" -> sender.sendMessage(describeStructureAsset(asset))
            "preview" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("Structure previews must be started by a player.")
                    return
                }
                previews.previewScene(
                    player,
                    label = asset.id.toString(),
                    scene = StructureAssetScenes.preview(asset),
                )
            }
        }
    }

    private fun assemblageCommand(sender: CommandSender, args: Array<out String>) {
        val operation = args.getOrNull(1)?.lowercase()
        if (operation in setOf("trace", "animate", "materialize")) {
            val mode = when (operation) {
                "animate" -> GenerationMode.PREVIEW
                "materialize" -> GenerationMode.MATERIALIZE
                else -> null
            }
            sequenceAssemblage(sender, args, mode)
            return
        }
        val firstToken = args.getOrNull(2)
        val secondToken = args.getOrNull(3)
        if (operation !in setOf("inspect", "preview") || firstToken == null || secondToken == null) {
            sender.sendMessage(
                "Usage: /mj assemblage inspect|preview <first-ref> <second-ref> [seed]"
            )
            return
        }
        val seedToken = args.getOrNull(4)
        val seed = seedToken?.toLongOrNull() ?: if (seedToken == null) {
            Random.nextLong()
        } else {
            sender.sendMessage("Invalid assemblage seed '$seedToken'.")
            return
        }
        val first = loadStructureAsset(sender, firstToken) ?: return
        val second = loadStructureAsset(sender, secondToken) ?: return
        val variants = orientedVariants(second)
        when (val outcome = ArchitecturalAssemblage.compose(
            AssemblageRequest(
                first = first,
                secondVariants = variants,
                placement = AssemblagePlacement.Search(targetSolidOverlap = 0.30),
                seed = seed,
                maxSolidPairEvaluations = ASSEMBLAGE_MAX_SOLID_PAIR_EVALUATIONS,
            )
        )) {
            is AssemblageOutcome.Rejected ->
                sender.sendMessage("Could not compose Structure Assets: ${outcome.reason}")
            is AssemblageOutcome.Generated -> {
                sender.sendMessage(describeAssemblageResult(first.id, second.id, outcome.result))
                if (operation == "preview") {
                    val player = sender as? Player
                    if (player == null) {
                        sender.sendMessage("Assemblage previews must be started by a player.")
                        return
                    }
                    previews.previewScene(
                        player,
                        label = "assemblage ${first.id} + ${second.id}",
                        scene = outcome.result.scene,
                    )
                }
            }
        }
    }

    private fun sequenceAssemblage(
        sender: CommandSender,
        args: Array<out String>,
        mode: GenerationMode?,
    ) {
        val player = sender as? Player
        if (mode != null && player == null) {
            sender.sendMessage("Assemblage animation and materialization must be started by a player.")
            return
        }
        val invocation = try {
            parseAssemblageSequenceInvocation(args.drop(2), Random::nextLong)
        } catch (_: IllegalArgumentException) {
            sender.sendMessage(
                "Usage: /mj assemblage trace|animate|materialize " +
                    "[seed] <first-ref> <second-ref> [more-refs...]"
            )
            return
        }
        val seed = invocation.seed
        val assetTokens = invocation.assetReferences
        val assets = ArrayList<StructureAsset>(assetTokens.size)
        for (token in assetTokens) {
            assets += loadStructureAsset(sender, token) ?: return
        }
        val request = AssemblageSequenceRequest(
            first = assets.first(),
            additions = assets.drop(1).map { asset ->
                AssemblageAddition(orientedVariants(asset))
            },
            targetSolidOverlap = 0.30,
            seed = seed,
            maxSolidPairEvaluations = ASSEMBLAGE_MAX_SOLID_PAIR_EVALUATIONS,
        )
        when (val outcome = ArchitecturalAssemblage.compose(request)) {
            is AssemblageSequenceOutcome.Rejected -> sender.sendMessage(
                "Could not add Structure Asset ${outcome.additionIndex + 2}: ${outcome.reason}"
            )
            is AssemblageSequenceOutcome.Generated -> {
                val trace = outcome.trace
                val collisions = trace.frames.drop(1).sumOf {
                    requireNotNull(it.placement).solidCollisionCount
                }
                sender.sendMessage(
                    "Composed ${trace.frames.size} Structure Assets with seed=${trace.seed}, " +
                        "size=${trace.finalScene.size.x}x${trace.finalScene.size.y}x" +
                        "${trace.finalScene.size.z}, collisions=$collisions."
                )
                for ((index, frame) in trace.frames.drop(1).withIndex()) {
                    val report = requireNotNull(frame.placement)
                    sender.sendMessage(
                        "- step ${index + 1}: ${frame.introducedSource.label} " +
                            "rotation=${report.secondRotation} " +
                            "offset=${report.secondOffset.x},${report.secondOffset.y}," +
                            "${report.secondOffset.z} collisions=${report.solidCollisionCount} " +
                            "seam=${report.localCoherence.doorwayCells}/" +
                            "${report.localCoherence.floorConnectionCells}/" +
                            "${report.localCoherence.supportCells}"
                    )
                }
                if (mode != null) {
                    try {
                        previews.previewTrace(
                            player = requireNotNull(player),
                            label = "architectural assemblage",
                            trace = trace,
                            changesPerTick = ASSEMBLAGE_ANIMATION_CHANGES_PER_TICK,
                            mode = mode,
                        )
                    } catch (exception: IllegalArgumentException) {
                        sender.sendMessage(
                            "Could not start assemblage ${mode.name.lowercase()}: " +
                                (exception.message ?: "invalid placement")
                        )
                    } catch (exception: IllegalStateException) {
                        sender.sendMessage(
                            "Could not start assemblage ${mode.name.lowercase()}: " +
                                (exception.message ?: "invalid world state")
                        )
                    }
                }
            }
        }
    }

    private fun hybridCommand(sender: CommandSender, args: Array<out String>) {
        val operation = args.getOrNull(1)?.lowercase()
        if (operation !in setOf("trace", "animate", "materialize")) {
            sender.sendMessage(
                "Usage: /mj hybrid trace|animate|materialize " +
                    "[seed] <model> <size> <asset-ref> [more-refs...]"
            )
            return
        }
        val invocation = try {
            parseHybridInvocation(args.drop(2), Random::nextLong)
        } catch (exception: IllegalArgumentException) {
            sender.sendMessage("Could not parse hybrid command: ${exception.message}")
            return
        }
        val hybridEnabled = try {
            previews.hasHybridModel(invocation.model)
        } catch (exception: Exception) {
            sender.sendMessage("Could not load model profile: ${exception.message}")
            return
        }
        if (!hybridEnabled) {
            sender.sendMessage(
                "Model '${invocation.model}' is not enabled for hybrid generation. " +
                    "Available hybrid models: ${previews.availableHybridModels().joinToString()}"
            )
            return
        }
        val assets = ArrayList<StructureAsset>(invocation.assetReferences.size)
        for (reference in invocation.assetReferences) {
            assets += loadStructureAsset(sender, reference) ?: return
        }
        val additions = assets.map { asset -> AssemblageAddition(orientedVariants(asset)) }
        try {
            if (operation == "trace") {
                previews.startHybridTrace(
                    sender = sender,
                    modelName = invocation.model,
                    requestedSize = invocation.size,
                    seed = invocation.seed,
                    additions = additions,
                    maxSolidPairEvaluations = ASSEMBLAGE_MAX_SOLID_PAIR_EVALUATIONS,
                    changesPerAdvance = ASSEMBLAGE_ANIMATION_CHANGES_PER_TICK,
                )
            } else {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("Hybrid animation and materialization require a player.")
                    return
                }
                previews.startHybrid(
                    player = player,
                    modelName = invocation.model,
                    requestedSize = invocation.size,
                    seed = invocation.seed,
                    additions = additions,
                    mode = if (operation == "animate") {
                        GenerationMode.PREVIEW
                    } else {
                        GenerationMode.MATERIALIZE
                    },
                    maxSolidPairEvaluations = ASSEMBLAGE_MAX_SOLID_PAIR_EVALUATIONS,
                    changesPerAdvance = ASSEMBLAGE_ANIMATION_CHANGES_PER_TICK,
                )
            }
        } catch (exception: IllegalArgumentException) {
            sender.sendMessage("Could not start hybrid generation: ${exception.message}")
        } catch (exception: IllegalStateException) {
            sender.sendMessage("Could not start hybrid generation: ${exception.message}")
        } catch (exception: ArithmeticException) {
            sender.sendMessage("Could not start hybrid generation: requested size is too large")
        }
    }

    private fun workbenchCommand(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("Asset Workbench commands require a player.")
            return
        }
        when (val operation = args.getOrNull(1)?.lowercase() ?: "open") {
            "open" -> workbench.open(
                player,
                args.drop(2).joinToString(" ").trim().takeIf(String::isNotEmpty),
            )
            "next" -> workbench.next(player)
            "prev" -> workbench.previous(player)
            "back" -> workbench.back(player)
            "pick" -> {
                val slot = args.getOrNull(2)?.toIntOrNull()
                if (slot == null) player.sendMessage("Usage: /mj workbench pick <slot>")
                else workbench.pick(player, slot)
            }
            "ports" -> workbench.togglePorts(player)
            "add" -> {
                val token = args.getOrNull(2)
                if (token.equals("all", ignoreCase = true)) {
                    workbench.addAll(
                        player,
                        args.drop(3).joinToString(" ").trim().takeIf(String::isNotEmpty),
                    )
                    return
                }
                val slot = token?.toIntOrNull()
                if (token != null && slot == null) {
                    player.sendMessage("Usage: /mj workbench add [slot]")
                } else {
                    workbench.add(player, slot)
                }
            }
            "tray" -> workbench.showTray(player)
            "remove" -> {
                val index = args.getOrNull(2)?.toIntOrNull()
                if (index == null) player.sendMessage("Usage: /mj workbench remove <index>")
                else workbench.remove(player, index)
            }
            "clear" -> workbench.clear(player)
            "grow" -> {
                val invocation = try {
                    parseWorkbenchGrowthInvocation(args.drop(2), Random::nextLong)
                } catch (exception: IllegalArgumentException) {
                    player.sendMessage(exception.message ?: "Invalid Workbench grow arguments")
                    return
                }
                startWorkbenchGrowth(player, invocation.count, invocation.seed)
            }
            "animate", "materialize" -> {
                val seedToken = args.getOrNull(2)
                val seed = seedToken?.toLongOrNull() ?: if (seedToken == null) {
                    Random.nextLong()
                } else {
                    player.sendMessage("Invalid Workbench seed '$seedToken'.")
                    return
                }
                startWorkbenchComposition(
                    player,
                    seed,
                    if (operation == "animate") {
                        GenerationMode.PREVIEW
                    } else {
                        GenerationMode.MATERIALIZE
                    },
                )
            }
            else -> player.sendMessage(
                "Usage: /mj workbench open [query] | next|prev|back | pick <slot> | " +
                    "ports | add [slot] | add all [search] | tray | remove <index> | clear | " +
                    "animate|materialize [seed] | grow [count] [seed]"
            )
        }
    }

    private fun debugWorldCommand(sender: CommandSender, args: Array<out String>) {
        when (args.getOrNull(1)?.lowercase() ?: "status") {
            "build" -> debugWorld.build(sender)
            "rebuild" -> debugWorld.rebuild(sender)
            "status" -> debugWorld.status(sender)
            "tp" -> {
                val player = sender as? Player
                if (player == null) sender.sendMessage("Debug world teleport requires a player.")
                else debugWorld.teleport(player)
            }
            "exit" -> {
                val player = sender as? Player
                if (player == null) sender.sendMessage("Debug world exit requires a player.")
                else debugWorld.exit(player)
            }
            else -> sender.sendMessage("Usage: /mj debugworld build|rebuild|status|tp|exit")
        }
    }

    private fun startWorkbenchComposition(
        player: Player,
        seed: Long,
        mode: GenerationMode,
    ) {
        val assets = workbench.trayAssets(player) ?: return
        val request = AssemblageSequenceRequest(
            first = assets.first(),
            additions = assets.drop(1).map { asset ->
                AssemblageAddition(orientedVariants(asset))
            },
            targetSolidOverlap = 0.30,
            seed = seed,
            maxSolidPairEvaluations = ASSEMBLAGE_MAX_SOLID_PAIR_EVALUATIONS,
        )
        when (val outcome = ArchitecturalAssemblage.compose(request)) {
            is AssemblageSequenceOutcome.Rejected -> player.sendMessage(
                "Workbench composition failed at source ${outcome.additionIndex + 2}: " +
                    outcome.reason
            )
            is AssemblageSequenceOutcome.Generated -> {
                val trace = outcome.trace
                player.sendMessage(
                    "Workbench composed ${trace.frames.size} assets, seed=${trace.seed}, " +
                        "size=${trace.finalScene.size.x}x${trace.finalScene.size.y}x" +
                        "${trace.finalScene.size.z}."
                )
                try {
                    previews.previewTrace(
                        player = player,
                        label = "Workbench tray",
                        trace = trace,
                        changesPerTick = ASSEMBLAGE_ANIMATION_CHANGES_PER_TICK,
                        mode = mode,
                    )
                } catch (exception: IllegalArgumentException) {
                    player.sendMessage(
                        "Could not place Workbench composition: " +
                            (exception.message ?: exception.javaClass.simpleName)
                    )
                }
            }
        }
    }

    private fun startWorkbenchGrowth(
        player: Player,
        count: Int,
        seed: Long,
    ) {
        val assets = workbench.growthAssets(
            player = player,
            count = count,
            seed = seed,
            maxPaletteAssets = MACRO_MAX_PALETTE_ASSETS,
        ) ?: return
        val paletteCells = assets.sumOf { asset ->
            asset.palettes.first().cells.size.toLong()
        }
        if (paletteCells > MACRO_PALETTE_CELL_LIMIT) {
            player.sendMessage(
                "Workbench macro palette has $paletteCells cells; " +
                    "limit=$MACRO_PALETTE_CELL_LIMIT. Use fewer or smaller assets."
            )
            return
        }
        val outcome = FrontierAssemblagePlanner.plan(
            FrontierAssemblageRequest(
                root = assets.first(),
                palette = assets.map { asset ->
                    AssemblageAddition(orientedVariants(asset))
                },
                targetInstances = count,
                seed = seed,
            )
        )
        val plan = outcome.plan
        if (plan.instances.size < 2) {
            player.sendMessage("Macro growth could not place a second Structure Asset instance.")
            return
        }
        if (outcome is FrontierAssemblageOutcome.Exhausted) {
            player.sendMessage(
                "Macro growth exhausted at ${plan.instances.size}/$count instances: ${outcome.reason}."
            )
        }
        try {
            val started = previews.startMacroAssemblage(
                player = player,
                program = MacroAssemblageProgram(plan),
                changesPerTick = MACRO_ANIMATION_CHANGES_PER_TICK,
            )
            if (started) {
                player.sendMessage("Macro seed=$seed; requested=$count, planned=${plan.instances.size}.")
            }
        } catch (exception: IllegalArgumentException) {
            player.sendMessage(
                "Could not start macro preview: " +
                    (exception.message ?: exception.javaClass.simpleName)
            )
        }
    }

    private fun orientedVariants(asset: StructureAsset): List<OrientedStructureAsset> =
        orientedStructureAssets.getOrPut(asset.id) {
            HorizontalRotation.entries.map { rotation ->
                OrientedStructureAsset(
                    rotation,
                    StructureAssetTransforms.rotateY(
                        asset,
                        rotation,
                        PaperAuthoredCellRotation,
                    ),
                )
            }
        }

    private fun assetReferenceCompletions(partial: String): List<String> =
        structureCatalog.selectReferences(StructureQuery(text = partial))
            .sortedWith(
                compareBy(
                    { !it.shortest.lowercase().startsWith(partial) },
                    { it.shortest.length },
                    { it.shortest },
                )
            )
            .take(MAX_ASSET_COMPLETIONS)
            .map { it.shortest }

    private fun loadStructureAsset(sender: CommandSender, token: String): StructureAsset? {
        val id = when (val resolution = structureCatalog.resolve(token)) {
            is AssetReferenceResolution.Resolved -> resolution.reference.id
            is AssetReferenceResolution.Missing -> {
                sender.sendMessage("No indexed Structure Asset matches '${resolution.input}'.")
                return null
            }
            is AssetReferenceResolution.Ambiguous -> {
                sender.sendMessage("Ambiguous Structure Asset '${resolution.input}':")
                for (candidate in resolution.candidates.take(12)) {
                    sender.sendMessage("- ${candidate.shortest} (${candidate.id})")
                }
                return null
            }
        }
        return when (val loaded = structureCatalog.load(id)) {
            is StructureLoadResult.Loaded -> loaded.asset
            is StructureLoadResult.Missing ->
                null.also { sender.sendMessage("Vanilla structure resource not found: ${loaded.id}") }
            is StructureLoadResult.NotIndexed ->
                null.also {
                    sender.sendMessage("Structure asset is outside the indexed catalog: ${loaded.id}")
                }
            is StructureLoadResult.Rejected ->
                null.also {
                    sender.sendMessage("Could not import ${loaded.id}: ${loaded.reason}")
                }
        }
    }

    private fun setPosition(player: Player, args: Array<out String>) {
        val location = player.location
        val corner = try {
            when (args.size) {
                1 -> BlockPoint(location.blockX, location.blockY, location.blockZ)
                4 -> BlockPoint(
                    parseBlockCoordinate(args[1], location.blockX),
                    parseBlockCoordinate(args[2], location.blockY),
                    parseBlockCoordinate(args[3], location.blockZ),
                )
                else -> {
                    player.sendMessage("Usage: /mj setpos [x y z]")
                    return
                }
            }
        } catch (exception: IllegalStateException) {
            player.sendMessage(exception.message ?: "Invalid generation position")
            return
        } catch (exception: ArithmeticException) {
            player.sendMessage("Generation position is outside the supported coordinate range")
            return
        }
        previews.setPosition(player, corner)
        player.sendMessage(
            "Generation bottom corner set to ${corner.x}, ${corner.y}, ${corner.z}."
        )
    }

    private fun showBounds(player: Player, args: Array<out String>) {
        val modelName = args.getOrNull(1)?.lowercase() ?: "maze"
        if (!previews.hasModel(modelName)) {
            player.sendMessage("Unknown model '$modelName'. Use /mj models to list models.")
            return
        }
        val heightToken = args.getOrNull(3)
        val height = heightToken?.toIntOrNull()
        if (heightToken != null && height == null) {
            player.sendMessage("Invalid height '$heightToken'.")
            return
        }
        try {
            val size = args.getOrNull(2)?.toIntOrNull() ?: previews.defaultSize(modelName)
            val bounds = previews.showBounds(player, modelName, size, height)
            player.sendMessage(
                "Showing $modelName bounds for 10 seconds: " +
                    "${bounds.minimum.x},${bounds.minimum.y},${bounds.minimum.z} to " +
                    "${bounds.maximum.x},${bounds.maximum.y},${bounds.maximum.z}."
            )
        } catch (exception: Exception) {
            player.sendMessage("Could not show bounds: ${exception.message}")
        }
    }

    companion object {
        private const val ASSEMBLAGE_MAX_SOLID_PAIR_EVALUATIONS = 1_000_000L
        private const val ASSEMBLAGE_ANIMATION_CHANGES_PER_TICK = 64
        private const val MACRO_ANIMATION_CHANGES_PER_TICK = 512
        private const val MACRO_PALETTE_CELL_LIMIT = 250_000L
        private const val MACRO_MAX_PALETTE_ASSETS = 24
        private const val MAX_ASSET_COMPLETIONS = 50
        private val ROOT_COMMANDS = listOf(
            "preview",
            "materialize",
            "stop",
            "setpos",
            "bound",
            "clearpos",
            "models",
            "assets",
            "asset",
            "assemblage",
            "hybrid",
            "workbench",
            "debugworld",
        )
    }
}
