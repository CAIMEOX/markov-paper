package com.caimeo.markovpaper.fabric

import com.mojang.serialization.Lifecycle
import io.netty.buffer.Unpooled
import net.minecraft.SharedConstants
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.MappedRegistry
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class FabricBlockUpdatesTest {
    @Test
    fun `native packets preserve exact cells including air and negative section coordinates`() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        val lookup = VanillaRegistries.createWorldLookup()
        val biomes = MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable())
        Registry.register(biomes, Biomes.PLAINS, lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value())
        biomes.freeze()
        val factory = PalettedContainerFactory.create(RegistryAccess.ImmutableRegistryAccess(listOf(BuiltInRegistries.BLOCK, biomes)))
        val updates = mapOf(
            BlockPos(-17, -65, -1) to Blocks.STONE.defaultBlockState(),
            BlockPos(-18, -65, -2) to Blocks.AIR.defaultBlockState(),
            BlockPos(-16, 0, 16) to BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:gray_stained_glass", false).blockState(),
            BlockPos(31, 63, 32) to Blocks.SEA_LANTERN.defaultBlockState(),
        )
        val packets = FabricBlockUpdates.packets(updates, factory)
        assertEquals(updates.keys.map(SectionPos::of).toSet().size, packets.size)
        val received = linkedMapOf<BlockPos, BlockState>()
        for (packet in packets) {
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.encode(buffer, packet)
                ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buffer).runUpdates { position, state ->
                    received[position.immutable()] = state
                }
            } finally { buffer.release() }
        }
        assertEquals(updates, received)
    }
}
