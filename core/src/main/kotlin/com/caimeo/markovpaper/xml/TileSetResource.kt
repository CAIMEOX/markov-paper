package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.engine.TileSet
import com.caimeo.markovpaper.engine.TileVariant
import org.w3c.dom.Element

object TileSetResource {
    fun load(
        name: String,
        outputSymbols: String,
        resources: ModelResources = ModelResources(),
        tiles: String = name,
    ): TileSet {
        val root = XmlDocuments.parse(resources.open("tilesets/$name.xml"))
        require(root.tagName == "tileset") { "Expected <tileset> in '$name.xml'" }
        require(root.optionalAttribute("fullSymmetry")?.lowercase() !in setOf("true", "1")) {
            "fullSymmetry tilesets are not supported; use horizontal tile symmetries"
        }
        if (root.optionalAttribute("nonUniform")?.lowercase() in setOf("true", "1")) {
            return NonUniformTileSetResource.load(root, outputSymbols, resources, tiles)
        }
        val tileElements = root.direct("tiles").single().direct("tile")
        require(tileElements.isNotEmpty()) { "Tileset '$name' has no tiles" }
        require(tileElements.map { it.getAttribute("name") }.distinct().size == tileElements.size) {
            "Duplicate tile name in '$name'"
        }
        val rawTiles = tileElements.map { tile ->
            val tileName = tile.getAttribute("name")
            val stream = resources.open("tilesets/$tiles/$tileName.vox")
            Triple(tileName, tile.doubleAttribute("weight", 1.0), VoxelResource.readRaw(stream))
        }
        val sizeX = rawTiles.first().third.sizeX
        val sizeY = rawTiles.first().third.sizeY
        val sizeZ = rawTiles.first().third.sizeZ
        require(sizeX == sizeY) { "WFC tiles must have square X/Y dimensions" }
        require(rawTiles.all { it.third.sizeX == sizeX && it.third.sizeY == sizeY && it.third.sizeZ == sizeZ }) {
            "All tiles in '$name' must have the same dimensions"
        }

        val uniqueColors = ArrayList<Int>()
        val baseTiles = linkedMapOf<String, ByteArray>()
        for ((tileName, _, raw) in rawTiles) {
            baseTiles[tileName] = ByteArray(raw.cells.size) { index ->
                val color = raw.cells[index]
                var ordinal = uniqueColors.indexOf(color)
                if (ordinal < 0) {
                    ordinal = uniqueColors.size
                    uniqueColors += color
                }
                require(ordinal < outputSymbols.length) {
                    "Tileset '$name' uses more colors than its values alphabet"
                }
                ordinal.toByte()
            }
        }

        fun transform(value: (Int, Int, Int) -> Byte): ByteArray =
            ByteArray(sizeX * sizeY * sizeZ) { index ->
                value(index % sizeX, index / sizeX % sizeY, index / (sizeX * sizeY))
            }
        fun zRotate(tile: ByteArray): ByteArray = transform { x, y, z ->
            tile[y + (sizeX - 1 - x) * sizeX + z * sizeX * sizeY]
        }
        fun xReflect(tile: ByteArray): ByteArray = transform { x, y, z ->
            tile[sizeX - 1 - x + y * sizeX + z * sizeX * sizeY]
        }
        fun yReflect(tile: ByteArray): ByteArray = transform { x, y, z ->
            tile[x + (sizeY - 1 - y) * sizeX + z * sizeX * sizeY]
        }
        fun symmetries(tile: ByteArray): List<ByteArray> = buildList {
            var rotated = tile
            repeat(4) {
                add(rotated)
                add(xReflect(rotated))
                rotated = zRotate(rotated)
            }
        }

        val weights = rawTiles.associate { it.first to it.second }
        val variants = buildList {
            for ((tileName, tile) in baseTiles) {
                for (variant in symmetries(tile).distinctBy(ByteArray::toList)) {
                    add(TileVariant(tileName, variant, requireNotNull(weights[tileName])))
                }
            }
        }
        data class NamedTile(val name: String, val cells: ByteArray)
        fun index(tile: NamedTile): Int {
            val result = variants.indexOfFirst { it.name == tile.name && it.voxels.contentEquals(tile.cells) }
            require(result >= 0) { "Tileset '$name' references a missing orientation" }
            return result
        }
        fun described(description: String): NamedTile {
            val parts = description.trim().split(Regex("\\s+"))
            require(parts.size in 1..2) { "Invalid tile orientation '$description' in '$name'" }
            var tile = requireNotNull(baseTiles[parts.last()]) {
                "Unknown tile '${parts.last()}' in '$name'"
            }
            val actions = if (parts.size == 2) parts[0] else ""
            for (position in actions.lastIndex downTo 0) {
                tile = when (actions[position]) {
                    'z' -> zRotate(tile)
                    else -> error("Unsupported tile rotation '${actions[position]}' in '$name'")
                }
            }
            return NamedTile(parts.last(), tile)
        }
        fun zRotate(tile: NamedTile) = tile.copy(cells = zRotate(tile.cells))
        fun xReflect(tile: NamedTile) = tile.copy(cells = xReflect(tile.cells))
        fun yReflect(tile: NamedTile) = tile.copy(cells = yReflect(tile.cells))
        fun symmetries(tile: NamedTile) = symmetries(tile.cells).map { tile.copy(cells = it) }

        val neighbors = Array(6) { Array(variants.size) { BooleanArray(variants.size) } }
        fun allow(direction: Int, first: NamedTile, second: NamedTile) {
            neighbors[direction][index(first)][index(second)] = true
        }
        for (neighbor in root.direct("neighbors").single().direct("neighbor")) {
            val leftText = neighbor.optionalAttribute("left")
            if (leftText != null) {
                val left = described(leftText)
                val right = described(neighbor.getAttribute("right"))
                allow(0, left, right)
                allow(0, yReflect(left), yReflect(right))
                allow(0, xReflect(right), xReflect(left))
                allow(0, yReflect(xReflect(right)), yReflect(xReflect(left)))
                val down = zRotate(left)
                val up = zRotate(right)
                allow(1, down, up)
                allow(1, xReflect(down), xReflect(up))
                allow(1, yReflect(up), yReflect(down))
                allow(1, xReflect(yReflect(up)), xReflect(yReflect(down)))
            } else {
                val top = symmetries(described(neighbor.getAttribute("top")))
                val bottom = symmetries(described(neighbor.getAttribute("bottom")))
                for (orientation in top.indices) allow(4, bottom[orientation], top[orientation])
            }
        }
        for (first in variants.indices) {
            for (second in variants.indices) {
                neighbors[2][second][first] = neighbors[0][first][second]
                neighbors[3][second][first] = neighbors[1][first][second]
                neighbors[5][second][first] = neighbors[4][first][second]
            }
        }
        return TileSet(sizeX, sizeY, sizeZ, variants, neighbors)
    }
}

private fun Element.direct(tagName: String): List<Element> = buildList {
    for (index in 0 until childNodes.length) {
        val child = childNodes.item(index)
        if (child is Element && child.tagName == tagName) add(child)
    }
}

private fun Element.optionalAttribute(name: String): String? =
    getAttribute(name).takeIf(String::isNotEmpty)

private fun Element.doubleAttribute(name: String, default: Double): Double =
    optionalAttribute(name)?.toDouble() ?: default
