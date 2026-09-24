package com.caimeo.markovpaper.assemblage

enum class HorizontalRotation {
    NONE,
    CLOCKWISE_90,
    CLOCKWISE_180,
    CLOCKWISE_270,
}

fun interface AuthoredCellRotation {
    fun rotate(cell: AuthoredCell, rotation: HorizontalRotation): AuthoredCell

    companion object {
        val IDENTITY = AuthoredCellRotation { cell, _ -> cell }
    }
}

object StructureAssetTransforms {
    fun rotateY(
        asset: StructureAsset,
        rotation: HorizontalRotation,
        cellRotation: AuthoredCellRotation = AuthoredCellRotation.IDENTITY,
    ): StructureAsset {
        if (rotation == HorizontalRotation.NONE) return asset
        val rotatedSize = when (rotation) {
            HorizontalRotation.NONE, HorizontalRotation.CLOCKWISE_180 -> asset.size
            HorizontalRotation.CLOCKWISE_90, HorizontalRotation.CLOCKWISE_270 ->
                Extent3i(asset.size.z, asset.size.y, asset.size.x)
        }
        return asset.copy(
            size = rotatedSize,
            palettes = asset.palettes.map { palette ->
                AuthoredPalette(
                    palette.cells.mapKeys { (position, _) ->
                        rotatePosition(position, asset.size, rotation)
                    }.mapValues { (_, cell) ->
                        when (cell) {
                            is AuthoredCell.Block, is AuthoredCell.Control ->
                                cellRotation.rotate(cell, rotation)
                            AuthoredCell.AuthoredAir, AuthoredCell.StructureVoid -> cell
                        }
                    }
                )
            },
        )
    }

    private fun rotatePosition(
        position: Vec3i,
        size: Extent3i,
        rotation: HorizontalRotation,
    ): Vec3i = when (rotation) {
        HorizontalRotation.NONE -> position
        HorizontalRotation.CLOCKWISE_90 ->
            Vec3i(size.z - 1 - position.z, position.y, position.x)
        HorizontalRotation.CLOCKWISE_180 ->
            Vec3i(size.x - 1 - position.x, position.y, size.z - 1 - position.z)
        HorizontalRotation.CLOCKWISE_270 ->
            Vec3i(position.z, position.y, size.x - 1 - position.x)
    }
}
