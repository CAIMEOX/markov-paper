package com.caimeo.markovpaper.assemblage

data class AssetSummary(
    val id: AssetId,
    val tags: Set<AssetTag>,
)

data class AssetScope(
    val requiredTags: Set<AssetTag> = emptySet(),
)

data class StructureQuery(
    val requiredTags: Set<AssetTag> = emptySet(),
    val text: String? = null,
)

data class AssetReference(
    val id: AssetId,
    val shortest: String,
)

sealed interface AssetReferenceResolution {
    data class Resolved(val reference: AssetReference) : AssetReferenceResolution

    data class Ambiguous(
        val input: String,
        val candidates: List<AssetReference>,
    ) : AssetReferenceResolution

    data class Missing(val input: String) : AssetReferenceResolution
}

class StructureManifest private constructor(
    val revision: CatalogRevision,
    val assets: List<AssetSummary>,
) {
    companion object {
        fun parse(revision: CatalogRevision, text: String): StructureManifest {
            val assets = text.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .map { line ->
                    val fields = line.split('|', limit = 2)
                    val tags = fields.getOrNull(1).orEmpty()
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .map(::AssetTag)
                        .toSet()
                    AssetSummary(AssetId(fields.first()), tags)
                }
                .toList()
            require(assets.map(AssetSummary::id).distinct().size == assets.size) {
                "Structure manifest contains duplicate asset ids"
            }
            return StructureManifest(revision, assets)
        }
    }
}

object BundledStructureManifests {
    fun load(resourcePath: String, revision: CatalogRevision): StructureManifest {
        require(resourcePath.startsWith('/') && ".." !in resourcePath) {
            "Bundled manifest path must be an absolute classpath resource"
        }
        val text = requireNotNull(
            BundledStructureManifests::class.java.getResourceAsStream(resourcePath)
        ) {
            "No bundled structure manifest at $resourcePath"
        }.bufferedReader().use { it.readText() }
        return StructureManifest.parse(revision, text)
    }
}

fun interface StructureAssetSource {
    fun load(id: AssetId): StructureLoadResult
}

sealed interface StructureLoadResult {
    data class Loaded(val asset: StructureAsset) : StructureLoadResult

    data class Missing(val id: AssetId) : StructureLoadResult

    data class Rejected(val id: AssetId, val reason: String) : StructureLoadResult

    data class NotIndexed(val id: AssetId) : StructureLoadResult
}

class StructureLibrary(
    private val manifest: StructureManifest,
    private val source: StructureAssetSource,
) {
    fun open(scope: AssetScope = AssetScope()): CatalogSnapshot {
        val assets = manifest.assets.filter { it.tags.containsAll(scope.requiredTags) }
        return CatalogSnapshot(manifest.revision, assets, source)
    }
}

class CatalogSnapshot internal constructor(
    val revision: CatalogRevision,
    assets: List<AssetSummary>,
    private val source: StructureAssetSource,
) {
    val assets: List<AssetSummary> = assets.toList()
    private val byId = this.assets.associateBy(AssetSummary::id)
    private val bySuffix = buildMap<String, MutableList<AssetSummary>> {
        for (asset in assets) {
            for (suffix in suffixes(asset.id)) getOrPut(suffix, ::ArrayList) += asset
        }
    }
    private val loadedAssets = HashMap<AssetId, StructureLoadResult>()

    fun resolve(input: String): AssetReferenceResolution {
        val normalized = input.trim().lowercase()
        if (normalized.isEmpty()) return AssetReferenceResolution.Missing(input)
        val exactId = runCatching {
            AssetId(if (':' in normalized) normalized else "minecraft:$normalized")
        }.getOrNull()
        byId[exactId]?.let { return AssetReferenceResolution.Resolved(referenceFor(it)) }
        if (':' in normalized) return AssetReferenceResolution.Missing(input)
        val matches = bySuffix[normalized].orEmpty()
        return when (matches.size) {
            0 -> AssetReferenceResolution.Missing(input)
            1 -> AssetReferenceResolution.Resolved(referenceFor(matches.single()))
            else -> AssetReferenceResolution.Ambiguous(
                input = input,
                candidates = matches.sortedBy { it.id.value }.map(::referenceFor),
            )
        }
    }

    fun select(query: StructureQuery = StructureQuery()): List<AssetSummary> {
        val text = query.text?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        return assets.filter { asset ->
            asset.tags.containsAll(query.requiredTags) &&
                (text == null || asset.id.value.lowercase().contains(text) ||
                    asset.tags.any { it.value.lowercase().contains(text) })
        }
    }

    fun selectReferences(query: StructureQuery = StructureQuery()): List<AssetReference> =
        select(query).map(::referenceFor)

    fun load(id: AssetId): StructureLoadResult {
        val summary = byId[id] ?: return StructureLoadResult.NotIndexed(id)
        return loadedAssets.getOrPut(id) {
            when (val loaded = source.load(id)) {
                is StructureLoadResult.Loaded -> loaded.copy(
                    asset = loaded.asset.copy(tags = summary.tags)
                )
                else -> loaded
            }
        }
    }

    private fun referenceFor(asset: AssetSummary): AssetReference {
        val shortest = suffixes(asset.id).firstOrNull { bySuffix[it]?.size == 1 }
            ?: asset.id.toString()
        return AssetReference(asset.id, shortest)
    }

    private fun suffixes(id: AssetId): List<String> {
        val segments = id.value.substringAfter(':').split('/')
        return segments.indices.map { start -> segments.drop(start).joinToString("/") }.reversed()
    }
}
