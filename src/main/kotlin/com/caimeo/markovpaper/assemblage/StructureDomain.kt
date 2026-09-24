package com.caimeo.markovpaper.assemblage

@JvmInline
value class AssetId(val value: String) {
    init {
        require(value.count { it == ':' } == 1 && value.substringAfter(':').isNotBlank()) {
            "Asset id must be a namespaced key: '$value'"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class AssetTag(val value: String) {
    init {
        require(value.isNotBlank()) { "Asset tag cannot be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class CatalogRevision(val value: String) {
    init {
        require(value.isNotBlank()) { "Catalog revision cannot be blank" }
    }
}

data class Extent3i(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(x > 0 && y > 0 && z > 0) { "Structure extent must be positive" }
    }

    fun contains(position: Vec3i): Boolean =
        position.x in 0 until x && position.y in 0 until y && position.z in 0 until z
}

data class Vec3i(
    val x: Int,
    val y: Int,
    val z: Int,
)

data class BlockStateSpec(val canonical: String) {
    init {
        require(canonical.isNotBlank()) { "Block state cannot be blank" }
    }
}

sealed interface AuthoredCell {
    data class Block(val state: BlockStateSpec) : AuthoredCell

    data object AuthoredAir : AuthoredCell

    data object StructureVoid : AuthoredCell

    data class Control(
        val kind: ControlKind,
        val state: BlockStateSpec,
        val orientation: String? = null,
    ) : AuthoredCell
}

enum class ControlKind {
    JIGSAW,
    STRUCTURE_BLOCK,
}

class AuthoredPalette(cells: Map<Vec3i, AuthoredCell>) {
    val cells: Map<Vec3i, AuthoredCell> = cells.toMap()

    operator fun get(position: Vec3i): AuthoredCell? = cells[position]

    override fun equals(other: Any?): Boolean = other is AuthoredPalette && cells == other.cells

    override fun hashCode(): Int = cells.hashCode()

    override fun toString(): String = "AuthoredPalette(cells=$cells)"
}

data class StructureAsset(
    val id: AssetId,
    val size: Extent3i,
    val palettes: List<AuthoredPalette>,
    val tags: Set<AssetTag>,
) {
    init {
        require(palettes.isNotEmpty()) { "Structure asset must contain at least one palette" }
        require(palettes.all { palette -> palette.cells.keys.all(size::contains) }) {
            "Structure asset contains an authored cell outside its declared size"
        }
    }
}
