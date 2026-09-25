package com.caimeo.markovpaper.fabric

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainerFactory

internal object FabricBlockUpdates {
    fun packets(updates: Map<BlockPos, BlockState>, factory: PalettedContainerFactory): List<ClientboundSectionBlocksUpdatePacket> =
        updates.entries.groupBy { SectionPos.of(it.key) }.map { (section, cells) ->
            val snapshot = LevelChunkSection(factory)
            val positions = ShortOpenHashSet(cells.size)
            for ((position, state) in cells) {
                positions.add(SectionPos.sectionRelativePos(position))
                snapshot.setBlockState(position.x and 15, position.y and 15, position.z and 15, state)
            }
            ClientboundSectionBlocksUpdatePacket(section, positions, snapshot)
        }
}
