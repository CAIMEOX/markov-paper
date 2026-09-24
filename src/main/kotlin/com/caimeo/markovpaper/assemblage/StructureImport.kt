package com.caimeo.markovpaper.assemblage

data class ImportedStructure(
    val size: Extent3i,
    val palettes: List<ImportedPalette>,
)

data class ImportedPalette(val cells: List<ImportedCell>)

data class ImportedCell(
    val position: Vec3i,
    val state: BlockStateSpec,
    val kind: ImportedCellKind,
    val orientation: String? = null,
)

enum class ImportedCellKind {
    BLOCK,
    AUTHORED_AIR,
    STRUCTURE_VOID,
    JIGSAW,
    STRUCTURE_BLOCK,
}

object StructureAssetNormalizer {
    fun normalize(id: AssetId, imported: ImportedStructure): StructureLoadResult {
        if (imported.palettes.isEmpty()) {
            return StructureLoadResult.Rejected(id, "Structure has no palettes")
        }
        val normalized = ArrayList<AuthoredPalette>(imported.palettes.size)
        for ((paletteIndex, importedPalette) in imported.palettes.withIndex()) {
            if (importedPalette.cells.map(ImportedCell::position).distinct().size !=
                importedPalette.cells.size
            ) {
                return StructureLoadResult.Rejected(
                    id,
                    "Palette $paletteIndex contains duplicate authored positions",
                )
            }
            if (importedPalette.cells.any { !imported.size.contains(it.position) }) {
                return StructureLoadResult.Rejected(
                    id,
                    "Palette $paletteIndex contains a cell outside the declared size",
                )
            }
            normalized += AuthoredPalette(
                importedPalette.cells.associate { cell -> cell.position to cell.normalize() }
            )
        }
        return StructureLoadResult.Loaded(
            StructureAsset(
                id = id,
                size = imported.size,
                palettes = normalized,
                tags = emptySet(),
            )
        )
    }

    private fun ImportedCell.normalize(): AuthoredCell = when (kind) {
        ImportedCellKind.BLOCK -> AuthoredCell.Block(state)
        ImportedCellKind.AUTHORED_AIR -> AuthoredCell.AuthoredAir
        ImportedCellKind.STRUCTURE_VOID -> AuthoredCell.StructureVoid
        ImportedCellKind.JIGSAW -> AuthoredCell.Control(ControlKind.JIGSAW, state, orientation)
        ImportedCellKind.STRUCTURE_BLOCK ->
            AuthoredCell.Control(ControlKind.STRUCTURE_BLOCK, state, orientation)
    }
}
