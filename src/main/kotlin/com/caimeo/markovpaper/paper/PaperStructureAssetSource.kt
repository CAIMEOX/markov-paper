package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.ImportedCell
import com.caimeo.markovpaper.assemblage.ImportedCellKind
import com.caimeo.markovpaper.assemblage.ImportedPalette
import com.caimeo.markovpaper.assemblage.ImportedStructure
import com.caimeo.markovpaper.assemblage.StructureAssetNormalizer
import com.caimeo.markovpaper.assemblage.StructureAssetSource
import com.caimeo.markovpaper.assemblage.StructureLoadResult
import com.caimeo.markovpaper.assemblage.Vec3i
import org.bukkit.NamespacedKey
import org.bukkit.block.data.type.Jigsaw
import org.bukkit.structure.StructureManager

class PaperStructureAssetSource(
    private val structures: StructureManager,
) : StructureAssetSource {
    override fun load(id: AssetId): StructureLoadResult {
        val key = NamespacedKey.fromString(id.value)
            ?: return StructureLoadResult.Rejected(id, "Invalid namespaced key")
        return try {
            val structure = structures.loadStructure(key, false)
                ?: return StructureLoadResult.Missing(id)
            val size = structure.size
            StructureAssetNormalizer.normalize(
                id,
                ImportedStructure(
                    size = Extent3i(size.blockX, size.blockY, size.blockZ),
                    palettes = structure.palettes.map { palette ->
                        ImportedPalette(
                            palette.blocks.map { state ->
                                val data = state.blockData
                                val materialKey = data.material.key.toString()
                                ImportedCell(
                                    position = Vec3i(state.x, state.y, state.z),
                                    state = BlockStateSpec(data.getAsString(false)),
                                    kind = PaperStructureCellKinds.classify(materialKey),
                                    orientation = (data as? Jigsaw)
                                        ?.orientation
                                        ?.name
                                        ?.lowercase(),
                                )
                            }
                        )
                    },
                )
            )
        } catch (exception: Exception) {
            StructureLoadResult.Rejected(
                id,
                exception.message ?: exception.javaClass.simpleName,
            )
        }
    }
}
