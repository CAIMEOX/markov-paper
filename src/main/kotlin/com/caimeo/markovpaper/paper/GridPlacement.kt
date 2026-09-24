package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.engine.GridView

data class BlockPoint(val x: Int, val y: Int, val z: Int)

data class GridPlacement(
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    val modelZIsUp: Boolean,
) {
    fun worldPosition(modelX: Int, modelY: Int, modelZ: Int): BlockPoint =
        if (modelZIsUp) {
            BlockPoint(originX + modelX, originY + modelZ, originZ + modelY)
        } else {
            BlockPoint(originX + modelX, originY + modelY, originZ + modelZ)
        }

    companion object {
        fun atBottomCorner(
            corner: BlockPoint,
            modelZIsUp: Boolean,
        ): GridPlacement = GridPlacement(
            originX = corner.x,
            originY = corner.y,
            originZ = corner.z,
            modelZIsUp = modelZIsUp,
        )

        fun centered(
            center: BlockPoint,
            grid: GridView,
            modelZIsUp: Boolean,
        ): GridPlacement = centered(
            center = center,
            sizeX = grid.sizeX,
            sizeY = grid.sizeY,
            sizeZ = grid.sizeZ,
            modelZIsUp = modelZIsUp,
        )

        fun centered(
            center: BlockPoint,
            sizeX: Int,
            sizeY: Int,
            sizeZ: Int,
            modelZIsUp: Boolean,
        ): GridPlacement {
            require(sizeX > 0 && sizeY > 0 && sizeZ > 0)
            val verticalSize = if (modelZIsUp) sizeZ else sizeY
            val depthSize = if (modelZIsUp) sizeY else sizeZ
            return GridPlacement(
                originX = center.x - sizeX / 2,
                originY = center.y - verticalSize / 2,
                originZ = center.z - depthSize / 2,
                modelZIsUp = modelZIsUp,
            )
        }
    }
}

data class WorldBounds(
    val minimum: BlockPoint,
    val maximum: BlockPoint,
) {
    init {
        require(minimum.x <= maximum.x && minimum.y <= maximum.y && minimum.z <= maximum.z)
    }

    fun contains(point: BlockPoint): Boolean =
        point.x in minimum.x..maximum.x &&
            point.y in minimum.y..maximum.y &&
            point.z in minimum.z..maximum.z

    fun edgePoints(maxSamplesPerEdge: Int = 64): Sequence<BlockPoint> = sequence {
        require(maxSamplesPerEdge > 0)
        val xs = samples(minimum.x, maximum.x, maxSamplesPerEdge)
        val ys = samples(minimum.y, maximum.y, maxSamplesPerEdge)
        val zs = samples(minimum.z, maximum.z, maxSamplesPerEdge)
        for (x in xs) {
            for (y in listOf(minimum.y, maximum.y)) {
                for (z in listOf(minimum.z, maximum.z)) yield(BlockPoint(x, y, z))
            }
        }
        for (y in ys) {
            for (x in listOf(minimum.x, maximum.x)) {
                for (z in listOf(minimum.z, maximum.z)) yield(BlockPoint(x, y, z))
            }
        }
        for (z in zs) {
            for (x in listOf(minimum.x, maximum.x)) {
                for (y in listOf(minimum.y, maximum.y)) yield(BlockPoint(x, y, z))
            }
        }
    }

    companion object {
        fun fromModel(
            placement: GridPlacement,
            sizeX: Int,
            sizeY: Int,
            sizeZ: Int,
        ): WorldBounds {
            require(sizeX > 0 && sizeY > 0 && sizeZ > 0)
            val first = placement.worldPosition(0, 0, 0)
            val last = placement.worldPosition(sizeX - 1, sizeY - 1, sizeZ - 1)
            return WorldBounds(
                minimum = BlockPoint(
                    minOf(first.x, last.x),
                    minOf(first.y, last.y),
                    minOf(first.z, last.z),
                ),
                maximum = BlockPoint(
                    maxOf(first.x, last.x),
                    maxOf(first.y, last.y),
                    maxOf(first.z, last.z),
                ),
            )
        }
    }
}

private fun samples(minimum: Int, maximum: Int, limit: Int): List<Int> {
    if (minimum == maximum || limit == 1) return listOf(minimum)
    val count = minOf(maximum.toLong() - minimum + 1L, limit.toLong()).toInt()
    return List(count) { index ->
        minimum + ((maximum - minimum).toLong() * index / (count - 1)).toInt()
    }.distinct()
}
