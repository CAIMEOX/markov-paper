package com.caimeo.markovpaper.assemblage

@JvmInline
value class SourceInstanceId(val value: String)

enum class SceneContributionKind(
    val isSolid: Boolean,
    val isInferred: Boolean,
    val isProcedural: Boolean,
) {
    BLOCK(isSolid = true, isInferred = false, isProcedural = false),
    AUTHORED_AIR(isSolid = false, isInferred = false, isProcedural = false),
    INFERRED_BLOCK(isSolid = true, isInferred = true, isProcedural = false),
    INFERRED_AIR(isSolid = false, isInferred = true, isProcedural = false),
    PROCEDURAL_BLOCK(isSolid = true, isInferred = false, isProcedural = true),
    PROCEDURAL_AIR(isSolid = false, isInferred = false, isProcedural = true),
}

enum class LocalCoherenceRole {
    DOORWAY,
    FLOOR_CONNECTION,
    SUPPORT,
}

sealed interface SceneProvenance {
    data class Authored(
        val instance: SourceInstanceId,
        val asset: AssetId,
        val sourceCell: Vec3i,
    ) : SceneProvenance

    data class Procedural(
        val model: String,
        val seed: Long,
        val symbol: Char,
        val sourceCell: Vec3i,
    ) : SceneProvenance {
        init {
            require(model.isNotBlank()) { "Procedural model name cannot be blank" }
        }
    }

    data class LocalCoherence(val role: LocalCoherenceRole) : SceneProvenance
}

data class SceneContribution(
    val provenance: SceneProvenance,
    val kind: SceneContributionKind,
    val state: BlockStateSpec,
) {
    init {
        when (val source = provenance) {
            is SceneProvenance.Authored -> {
                require(!kind.isInferred && !kind.isProcedural)
            }
            is SceneProvenance.Procedural -> {
                require(kind.isProcedural)
            }
            is SceneProvenance.LocalCoherence -> {
                require(kind.isInferred)
                require(
                    when (source.role) {
                        LocalCoherenceRole.DOORWAY ->
                            kind == SceneContributionKind.INFERRED_AIR
                        LocalCoherenceRole.FLOOR_CONNECTION, LocalCoherenceRole.SUPPORT ->
                            kind == SceneContributionKind.INFERRED_BLOCK
                    }
                )
            }
        }
    }
}

data class SceneCell(
    val state: BlockStateSpec,
    val selected: SceneContribution?,
    val contributions: List<SceneContribution>,
) {
    init {
        require(selected == null || selected in contributions)
        require(selected == null || selected.state == state)
    }

    val sources: Set<AssetId> = contributions.mapNotNull { (it.provenance as? SceneProvenance.Authored)?.asset }.toSet()
}

data class SceneSnapshot(
    val size: Extent3i,
    val cells: Map<Vec3i, SceneCell>,
)

object StructureAssetScenes {
    fun preview(asset: StructureAsset, paletteIndex: Int = 0): SceneSnapshot {
        val palette = asset.palettes.getOrNull(paletteIndex)
            ?: throw IllegalArgumentException(
                "Palette $paletteIndex is unavailable for ${asset.id}"
            )
        val cells = buildMap {
            for ((position, authored) in palette.cells) {
                val (state, kind) = when (authored) {
                    is AuthoredCell.Block -> authored.state to SceneContributionKind.BLOCK
                    AuthoredCell.AuthoredAir ->
                        BlockStateSpec("minecraft:air") to SceneContributionKind.AUTHORED_AIR
                    AuthoredCell.StructureVoid -> continue
                    is AuthoredCell.Control -> authored.state to SceneContributionKind.BLOCK
                }
                val contribution = SceneContribution(
                    provenance = SceneProvenance.Authored(SourceInstanceId("asset"), asset.id, position),
                    kind = kind,
                    state = state,
                )
                put(position, SceneCell(state, contribution, listOf(contribution)))
            }
        }
        return SceneSnapshot(asset.size, cells)
    }

    internal fun authoredContributions(
        asset: StructureAsset,
        instance: SourceInstanceId,
        origin: Vec3i,
        paletteIndex: Int = 0,
    ): Map<Vec3i, SceneContribution> {
        val palette = asset.palettes.getOrNull(paletteIndex)
            ?: throw IllegalArgumentException(
                "Palette $paletteIndex is unavailable for ${asset.id}"
            )
        return buildMap {
            for ((position, cell) in palette.cells) {
                val (kind, state) = when (cell) {
                    is AuthoredCell.Block -> SceneContributionKind.BLOCK to cell.state
                    AuthoredCell.AuthoredAir ->
                        SceneContributionKind.AUTHORED_AIR to BlockStateSpec("minecraft:air")
                    AuthoredCell.StructureVoid, is AuthoredCell.Control -> continue
                }
                put(
                    Vec3i(
                        position.x + origin.x,
                        position.y + origin.y,
                        position.z + origin.z,
                    ),
                    SceneContribution(
                        provenance = SceneProvenance.Authored(instance, asset.id, position),
                        kind = kind,
                        state = state,
                    ),
                )
            }
        }
    }
}
