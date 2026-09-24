package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AssemblageResult
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.StructureAsset
import java.util.Locale

data class AssemblageSequenceInvocation(
    val seed: Long,
    val assetReferences: List<String>,
)

data class HybridInvocation(
    val seed: Long,
    val model: String,
    val size: Int,
    val assetReferences: List<String>,
)

data class WorkbenchGrowthInvocation(
    val count: Int,
    val seed: Long,
)

fun parseWorkbenchGrowthInvocation(
    tokens: List<String>,
    randomSeed: () -> Long,
): WorkbenchGrowthInvocation {
    require(tokens.size <= 2) { "Workbench grow accepts only count and optional seed" }
    val count = tokens.firstOrNull()?.toIntOrNull() ?: if (tokens.isEmpty()) {
        50
    } else {
        throw IllegalArgumentException("Workbench grow count must be an integer")
    }
    require(count in 2..100) { "Workbench grow count must be in 2..100" }
    val seed = tokens.getOrNull(1)?.toLongOrNull() ?: if (tokens.size < 2) {
        randomSeed()
    } else {
        throw IllegalArgumentException("Workbench grow seed must be an integer")
    }
    return WorkbenchGrowthInvocation(count, seed)
}

fun parseHybridInvocation(
    tokens: List<String>,
    randomSeed: () -> Long,
): HybridInvocation {
    require(tokens.size >= 3) { "Hybrid generation needs a model, size, and Structure Asset" }
    val explicitSeed = tokens.first().toLongOrNull()
    val values = if (explicitSeed == null) tokens else tokens.drop(1)
    require(values.size >= 3) { "Hybrid generation needs a model, size, and Structure Asset" }
    val size = values[1].toIntOrNull()
        ?: throw IllegalArgumentException("Hybrid size must be an integer")
    require(size > 0) { "Hybrid size must be positive" }
    return HybridInvocation(
        seed = explicitSeed ?: randomSeed(),
        model = values.first().lowercase(),
        size = size,
        assetReferences = values.drop(2),
    )
}

fun parseAssemblageSequenceInvocation(
    tokens: List<String>,
    randomSeed: () -> Long,
): AssemblageSequenceInvocation {
    require(tokens.size >= 2) { "At least two Structure Asset references are required" }
    val explicitSeed = tokens.first().toLongOrNull()
    val assetReferences = if (explicitSeed == null) tokens else tokens.drop(1)
    require(assetReferences.size >= 2) {
        "At least two Structure Asset references are required"
    }
    return AssemblageSequenceInvocation(
        seed = explicitSeed ?: randomSeed(),
        assetReferences = assetReferences,
    )
}

fun parseStructureAssetId(input: String): AssetId {
    val value = input.trim()
    require(value.isNotEmpty()) { "Structure asset id cannot be blank" }
    return AssetId(if (':' in value) value else "minecraft:$value")
}

fun describeStructureAsset(asset: StructureAsset): String {
    val palette = asset.palettes.first()
    val air = palette.cells.values.count { it == AuthoredCell.AuthoredAir }
    val voids = palette.cells.values.count { it == AuthoredCell.StructureVoid }
    val controls = palette.cells.values.count { it is AuthoredCell.Control }
    return "${asset.id} size=${asset.size.x}x${asset.size.y}x${asset.size.z} " +
        "palettes=${asset.palettes.size} authored=${palette.cells.size} " +
        "air=$air void=$voids controls=$controls"
}

fun describeAssemblageResult(
    first: AssetId,
    second: AssetId,
    result: AssemblageResult,
): String {
    val report = result.report
    val overlap = String.format(Locale.ROOT, "%.1f", report.solidOverlapRatio * 100.0)
    return "$first + $second seed=${report.seed} " +
        "size=${result.scene.size.x}x${result.scene.size.y}x${result.scene.size.z} " +
        "rotation=${report.secondRotation} " +
        "offset=${report.secondOffset.x},${report.secondOffset.y},${report.secondOffset.z} " +
        "overlap=$overlap% collisions=${report.solidCollisionCount} " +
        "seam=${report.localCoherence.doorwayCells}/" +
        "${report.localCoherence.floorConnectionCells}/${report.localCoherence.supportCells}"
}
