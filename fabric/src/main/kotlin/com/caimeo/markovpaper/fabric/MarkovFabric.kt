package com.caimeo.markovpaper.fabric

import com.caimeo.markovpaper.minecraft.GenerationMode
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.TicketType
import net.minecraft.server.permissions.Permissions
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import kotlin.random.Random

class MarkovFabric : ModInitializer {
    private val logger = LoggerFactory.getLogger("markov")
    private val managers = IdentityHashMap<MinecraftServer, FabricPreviewManager>()

    override fun onInitialize() {
        val ticket = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("markov", "materialization"),
            TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING or TicketType.FLAG_KEEP_DIMENSION_ACTIVE))
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            managers[server] = FabricPreviewManager(server, FabricLoader.getInstance().configDir.resolve("markov"), ticket, logger)
        }
        ServerTickEvents.END_SERVER_TICK.register { server -> managers[server]?.tick() }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server -> managers[server]?.disconnect(handler.player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPING.register { server -> managers.remove(server)?.close() }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> register(dispatcher) }
        logger.info("Markov Fabric initialized")
    }

    private fun manager(source: CommandSourceStack) = requireNotNull(managers[source.server]) { "Markov runtime is unavailable" }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val root = literal("mj").requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
            .executes { context -> generate(context, GenerationMode.PREVIEW) }
        root.then(literal("models").executes { context -> safely(context) {
            context.source.sendSuccess({ Component.literal("Models: ${manager(context.source).models.names().joinToString()}") }, false)
        } })
        for ((name, mode) in listOf("preview" to GenerationMode.PREVIEW, "materialize" to GenerationMode.MATERIALIZE)) {
            val command = literal(name).executes { generate(it, mode) }
            optionalArguments(command, listOf("model" to StringArgumentType.word(), "size" to IntegerArgumentType.integer(1),
                "seed" to LongArgumentType.longArg(), "rewrites" to IntegerArgumentType.integer(1, 64),
                "height" to IntegerArgumentType.integer(1))) { generate(it, mode) }
            root.then(command)
        }
        val bound = literal("bound").executes(::showBounds)
        optionalArguments(bound, listOf("model" to StringArgumentType.word(), "size" to IntegerArgumentType.integer(1),
            "height" to IntegerArgumentType.integer(1)), ::showBounds)
        root.then(bound)
        root.then(literal("setpos").executes { context -> safely(context) {
            val player = context.source.playerOrException
            manager(context.source).setPosition(player, player.blockPosition())
        } }.then(argument("position", BlockPosArgument.blockPos()).executes { context -> safely(context) {
            manager(context.source).setPosition(context.source.playerOrException, BlockPosArgument.getBlockPos(context, "position"))
        } }))
        root.then(literal("clearpos").executes { context -> safely(context) {
            manager(context.source).clearPosition(context.source.playerOrException)
        } })
        root.then(literal("stop").executes { context -> safely(context) {
            if (manager(context.source).stop(context.source.playerOrException.uuid))
                context.source.sendSuccess({ Component.literal("Markov preview stopped.") }, false)
        } })
        dispatcher.register(root)
    }

    private fun optionalArguments(
        root: ArgumentBuilder<CommandSourceStack, *>,
        arguments: List<Pair<String, ArgumentType<*>>>,
        execute: (CommandContext<CommandSourceStack>) -> Int,
    ) {
        var child: ArgumentBuilder<CommandSourceStack, *>? = null
        for ((name, type) in arguments.asReversed()) {
            val node = argument(name, type).executes(execute)
            if (name == "model") node.suggests { context, builder ->
                SharedSuggestionProvider.suggest(manager(context.source).models.names(), builder)
            }
            child?.let(node::then)
            child = node
        }
        child?.let(root::then)
    }

    private fun generate(context: CommandContext<CommandSourceStack>, mode: GenerationMode): Int = safely(context) {
        manager(context.source).start(context.source.playerOrException,
            optional(context, "model", String::class.java) ?: "maze",
            optional(context, "size", Int::class.javaObjectType),
            optional(context, "seed", Long::class.javaObjectType) ?: Random.nextLong(),
            optional(context, "rewrites", Int::class.javaObjectType),
            optional(context, "height", Int::class.javaObjectType), mode)
    }

    private fun showBounds(context: CommandContext<CommandSourceStack>): Int = safely(context) {
        manager(context.source).showBounds(context.source.playerOrException,
            optional(context, "model", String::class.java) ?: "maze",
            optional(context, "size", Int::class.javaObjectType), optional(context, "height", Int::class.javaObjectType))
    }

    private fun <T> optional(context: CommandContext<CommandSourceStack>, name: String, type: Class<T>): T? =
        try { context.getArgument(name, type) } catch (_: IllegalArgumentException) { null }

    private fun safely(context: CommandContext<CommandSourceStack>, action: () -> Unit): Int = try {
        action()
        1
    } catch (exception: Exception) {
        context.source.sendFailure(Component.literal(exception.message ?: exception.javaClass.simpleName))
        0
    }
}
