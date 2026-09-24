package com.caimeo.markovpaper.workbench

import com.caimeo.markovpaper.assemblage.ControlKind
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.StructureAssetScenes
import com.caimeo.markovpaper.assemblage.Vec3i

data class GalleryPlacement(
    val slot: Int,
    val origin: Vec3i,
    val descriptor: AssetDescriptor,
)

data class GalleryControlMarker(
    val slot: Int,
    val position: Vec3i,
    val kind: ControlKind,
    val orientation: String?,
)

data class AssetGallery(
    val size: Extent3i,
    val scene: SceneSnapshot,
    val placements: List<GalleryPlacement>,
    val controls: List<GalleryControlMarker>,
)

fun buildAssetGallery(
    assets: List<StructureAsset>,
    columns: Int = 3,
    gap: Int = 4,
): AssetGallery {
    require(assets.isNotEmpty()) { "Gallery needs at least one Structure Asset" }
    require(columns > 0 && gap >= 0)
    val descriptors = assets.map(AssetDescriptors::describe)
    val usedColumns = minOf(columns, assets.size)
    val cellWidth = descriptors.maxOf { it.size.x } + gap
    val cellDepth = descriptors.maxOf { it.size.z } + gap
    val placements = descriptors.mapIndexed { index, descriptor ->
        GalleryPlacement(
            slot = index + 1,
            origin = Vec3i(
                x = index % usedColumns * cellWidth,
                y = 0,
                z = index / usedColumns * cellDepth,
            ),
            descriptor = descriptor,
        )
    }
    val cells = buildMap {
        for ((index, asset) in assets.withIndex()) {
            val origin = placements[index].origin
            for ((position, cell) in StructureAssetScenes.preview(asset).cells) {
                put(position + origin, cell)
            }
        }
    }
    val controls = placements.flatMap { placement ->
        placement.descriptor.controls.map { marker ->
            GalleryControlMarker(
                slot = placement.slot,
                position = marker.position + placement.origin,
                kind = marker.kind,
                orientation = marker.orientation,
            )
        }
    }
    val size = Extent3i(
        x = placements.maxOf { it.origin.x + it.descriptor.size.x },
        y = placements.maxOf { it.origin.y + it.descriptor.size.y },
        z = placements.maxOf { it.origin.z + it.descriptor.size.z },
    )
    return AssetGallery(
        size = size,
        scene = SceneSnapshot(size, cells),
        placements = placements,
        controls = controls,
    )
}

private operator fun Vec3i.plus(other: Vec3i): Vec3i =
    Vec3i(x + other.x, y + other.y, z + other.z)
