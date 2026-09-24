package com.caimeo.markovpaper.workbench

import com.caimeo.markovpaper.assemblage.Vec3i

data class DebugPlot(
    val index: Int,
    val origin: Vec3i,
    val descriptor: AssetDescriptor,
)

data class DebugWorldLayout(
    val plots: List<DebugPlot>,
    val strideX: Int,
    val strideZ: Int,
)

fun buildDebugWorldLayout(
    descriptors: List<AssetDescriptor>,
    columns: Int = 20,
    baseY: Int = 64,
    minimumStride: Int = 40,
    margin: Int = 2,
): DebugWorldLayout {
    require(descriptors.isNotEmpty())
    require(columns > 0 && minimumStride > 0 && margin >= 0)
    val strideX = maxOf(minimumStride, descriptors.maxOf { it.size.x } + margin * 2)
    val strideZ = maxOf(minimumStride, descriptors.maxOf { it.size.z } + margin * 2)
    val plots = descriptors.mapIndexed { index, descriptor ->
        DebugPlot(
            index = index,
            origin = Vec3i(
                x = index % columns * strideX + margin,
                y = baseY,
                z = index / columns * strideZ + margin,
            ),
            descriptor = descriptor,
        )
    }
    return DebugWorldLayout(plots, strideX, strideZ)
}
