package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.AuthoredCellRotation
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.HorizontalRotation
import org.bukkit.Bukkit
import org.bukkit.block.data.type.Jigsaw
import org.bukkit.block.structure.StructureRotation

fun HorizontalRotation.toPaperRotation(): StructureRotation = when (this) {
    HorizontalRotation.NONE -> StructureRotation.NONE
    HorizontalRotation.CLOCKWISE_90 -> StructureRotation.CLOCKWISE_90
    HorizontalRotation.CLOCKWISE_180 -> StructureRotation.CLOCKWISE_180
    HorizontalRotation.CLOCKWISE_270 -> StructureRotation.COUNTERCLOCKWISE_90
}

object PaperAuthoredCellRotation : AuthoredCellRotation {
    override fun rotate(cell: AuthoredCell, rotation: HorizontalRotation): AuthoredCell {
        if (rotation == HorizontalRotation.NONE) return cell
        return when (cell) {
            is AuthoredCell.Block -> cell.copy(state = rotateState(cell.state, rotation).first)
            is AuthoredCell.Control -> {
                val (state, orientation) = rotateState(cell.state, rotation)
                cell.copy(state = state, orientation = orientation ?: cell.orientation)
            }
            AuthoredCell.AuthoredAir, AuthoredCell.StructureVoid -> cell
        }
    }

    private fun rotateState(
        state: BlockStateSpec,
        rotation: HorizontalRotation,
    ): Pair<BlockStateSpec, String?> {
        val data = Bukkit.createBlockData(state.canonical)
        data.rotate(rotation.toPaperRotation())
        return BlockStateSpec(data.getAsString(false)) to
            (data as? Jigsaw)?.orientation?.name?.lowercase()
    }
}
