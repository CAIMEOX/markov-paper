package com.caimeo.markovpaper.workbench

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.ControlKind
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.Vec3i

enum class BoundaryFace {
    WEST,
    EAST,
    BOTTOM,
    TOP,
    NORTH,
    SOUTH,
}

data class BoundaryProfile(
    val solidCells: Int,
    val authoredAirCells: Int,
)

data class AssetControlMarker(
    val position: Vec3i,
    val kind: ControlKind,
    val orientation: String?,
)

data class AssetDescriptor(
    val id: AssetId,
    val size: Extent3i,
    val solidCells: Int,
    val authoredAirCells: Int,
    val controls: List<AssetControlMarker>,
    val materialCounts: Map<String, Int>,
    val boundaries: Map<BoundaryFace, BoundaryProfile>,
)

object AssetDescriptors {
    fun describe(asset: StructureAsset): AssetDescriptor {
        val palette = asset.palettes.first()
        val boundaryCounts = BoundaryFace.entries.associateWith {
            MutableBoundaryProfile()
        }
        val materialCounts = HashMap<String, Int>()
        val controls = ArrayList<AssetControlMarker>()
        var solids = 0
        var air = 0

        for ((position, cell) in palette.cells) {
            when (cell) {
                is AuthoredCell.Block -> {
                    solids++
                    val material = cell.state.canonical.substringBefore('[')
                    materialCounts[material] = (materialCounts[material] ?: 0) + 1
                    for (face in boundaryFaces(position, asset.size)) {
                        boundaryCounts.getValue(face).solidCells++
                    }
                }
                AuthoredCell.AuthoredAir -> {
                    air++
                    for (face in boundaryFaces(position, asset.size)) {
                        boundaryCounts.getValue(face).authoredAirCells++
                    }
                }
                is AuthoredCell.Control -> controls += AssetControlMarker(
                    position = position,
                    kind = cell.kind,
                    orientation = cell.orientation,
                )
                AuthoredCell.StructureVoid -> Unit
            }
        }
        return AssetDescriptor(
            id = asset.id,
            size = asset.size,
            solidCells = solids,
            authoredAirCells = air,
            controls = controls.sortedWith(
                compareBy({ it.position.y }, { it.position.z }, { it.position.x })
            ),
            materialCounts = materialCounts.toSortedMap(),
            boundaries = boundaryCounts.mapValues { (_, count) ->
                BoundaryProfile(count.solidCells, count.authoredAirCells)
            },
        )
    }

    private fun boundaryFaces(position: Vec3i, size: Extent3i): List<BoundaryFace> = buildList {
        if (position.x == 0) add(BoundaryFace.WEST)
        if (position.x == size.x - 1) add(BoundaryFace.EAST)
        if (position.y == 0) add(BoundaryFace.BOTTOM)
        if (position.y == size.y - 1) add(BoundaryFace.TOP)
        if (position.z == 0) add(BoundaryFace.NORTH)
        if (position.z == size.z - 1) add(BoundaryFace.SOUTH)
    }

    private data class MutableBoundaryProfile(
        var solidCells: Int = 0,
        var authoredAirCells: Int = 0,
    )
}
