package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.ImportedCellKind

internal object PaperStructureCellKinds {
    fun classify(materialKey: String): ImportedCellKind = when (materialKey) {
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air" ->
            ImportedCellKind.AUTHORED_AIR
        "minecraft:structure_void" -> ImportedCellKind.STRUCTURE_VOID
        "minecraft:jigsaw" -> ImportedCellKind.JIGSAW
        "minecraft:structure_block" -> ImportedCellKind.STRUCTURE_BLOCK
        else -> ImportedCellKind.BLOCK
    }
}
