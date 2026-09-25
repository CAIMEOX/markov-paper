package com.caimeo.markovpaper.minecraft

fun validateGenerationShape(
    placement: GridPlacement,
    sizeX: Int,
    sizeY: Int,
    sizeZ: Int,
    worldMinHeight: Int,
    worldMaxHeight: Int,
) {
    require(sizeX > 0 && sizeY > 0 && sizeZ > 0)
    val voxelCount = try {
        Math.multiplyExact(
            Math.multiplyExact(sizeX.toLong(), sizeY.toLong()),
            sizeZ.toLong(),
        )
    } catch (exception: ArithmeticException) {
        throw IllegalArgumentException("Generation dimensions overflow the voxel count", exception)
    }
    require(voxelCount <= MAX_GENERATION_VOXELS) {
        "Generation volume $voxelCount exceeds safety limit $MAX_GENERATION_VOXELS"
    }
    val verticalSize = if (placement.modelZIsUp) sizeZ else sizeY
    require(placement.originY >= worldMinHeight) {
        "Generation extends below world minimum Y $worldMinHeight"
    }
    require(placement.originY.toLong() + verticalSize <= worldMaxHeight.toLong()) {
        "Generation extends above world maximum Y ${worldMaxHeight - 1}"
    }
}

const val MAX_GENERATION_VOXELS = 2_000_000L
