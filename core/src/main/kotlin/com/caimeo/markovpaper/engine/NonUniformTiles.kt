package com.caimeo.markovpaper.engine

import java.util.ArrayDeque

data class AtomPoint(val x: Int, val y: Int, val z: Int) {
    operator fun plus(other: AtomPoint) = AtomPoint(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: AtomPoint) = AtomPoint(x - other.x, y - other.y, z - other.z)
}

/** Identity belongs to a module orientation and local offset, never to its material. */
data class TileAtom(val extent: AtomPoint, val offset: AtomPoint) {
    fun fits(x: Int, y: Int, z: Int, sizeX: Int, sizeY: Int, sizeZ: Int): Boolean {
        val origin = AtomPoint(x, y, z) - offset
        return origin.x >= 0 && origin.y >= 0 && origin.z >= 0 &&
            origin.x.toLong() + extent.x <= sizeX &&
            origin.y.toLong() + extent.y <= sizeY &&
            origin.z.toLong() + extent.z <= sizeZ
    }
}

/** Atom payloads have a shared voxel size; missing atoms are unowned space, not air. */
data class NonUniformTile(
    val name: String,
    val atoms: Map<AtomPoint, ByteArray>,
    val weight: Double = 1.0,
)

data class NonUniformNeighbor(
    val first: String,
    val second: String,
    val directions: List<Int>,
    val offset: AtomPoint? = null,
)

/** Compiles rigid polycubes into ordinary WFC states and six-direction constraints. */
object NonUniformTiles {
    val directions = listOf(
        AtomPoint(1, 0, 0), AtomPoint(0, 1, 0), AtomPoint(-1, 0, 0),
        AtomPoint(0, -1, 0), AtomPoint(0, 0, 1), AtomPoint(0, 0, -1),
    )
    private val opposite = intArrayOf(2, 3, 0, 1, 5, 4)

    fun compile(atomSize: AtomPoint, tiles: List<NonUniformTile>, rules: List<NonUniformNeighbor>): TileSet {
        require(atomSize.x > 0 && atomSize.y > 0 && atomSize.z > 0)
        val payloadSize = Math.multiplyExact(Math.multiplyExact(atomSize.x, atomSize.y), atomSize.z)
        require(tiles.isNotEmpty()) { "A NUT tileset must contain tiles" }
        val variants = ArrayList<TileVariant>()
        val indexes = tiles.map { tile ->
            require(tile.atoms.isNotEmpty()) { "NUT '${tile.name}' has no occupied atoms" }
            require(tile.atoms.keys.all { it.x >= 0 && it.y >= 0 && it.z >= 0 })
            require(tile.atoms.keys.minOf { it.x } == 0 && tile.atoms.keys.minOf { it.y } == 0 &&
                tile.atoms.keys.minOf { it.z } == 0) { "NUT '${tile.name}' must be normalized to its minimum corner" }
            require(tile.atoms.values.all { it.size == payloadSize }) { "NUT atom payload size differs from atomSize" }
            val reached = HashSet<AtomPoint>()
            val queue = ArrayDeque<AtomPoint>().apply { add(tile.atoms.keys.first()) }
            while (queue.isNotEmpty()) {
                val point = queue.removeFirst()
                if (!reached.add(point)) continue
                directions.map { point + it }.filter { it in tile.atoms && it !in reached }.forEach(queue::add)
            }
            require(reached.size == tile.atoms.size) { "NUT '${tile.name}' must be face-connected" }
            val extent = AtomPoint(tile.atoms.keys.maxOf { it.x } + 1, tile.atoms.keys.maxOf { it.y } + 1,
                tile.atoms.keys.maxOf { it.z } + 1)
            tile.atoms.mapValues { (point, voxels) ->
                variants.size.also {
                    // Equal total prior per module orientation, independent of its atom count.
                    variants += TileVariant(tile.name, voxels, tile.weight / tile.atoms.size, TileAtom(extent, point))
                }
            }
        }
        require(6L * variants.size * variants.size <= 64_000_000L) {
            "NUT adjacency exceeds 64 million entries; use coarser atoms or fewer module orientations"
        }
        val neighbors = Array(6) { Array(variants.size) { BooleanArray(variants.size) } }
        fun allow(direction: Int, first: Int, second: Int) {
            neighbors[direction][first][second] = true
            neighbors[opposite[direction]][second][first] = true
        }
        for (index in indexes) for ((point, state) in index) for ((direction, step) in directions.withIndex()) {
            index[point + step]?.let { allow(direction, state, it) }
        }

        val names = tiles.map { it.name }.toSet()
        for (rule in rules) {
            require(rule.first == "*" || rule.first in names) { "Unknown NUT '${rule.first}'" }
            require(rule.second == "*" || rule.second in names) { "Unknown NUT '${rule.second}'" }
            require(rule.directions.isNotEmpty() && rule.directions.all { it in directions.indices })
            for (a in tiles.indices) for (b in tiles.indices) {
                if (rule.first != "*" && rule.first != tiles[a].name ||
                    rule.second != "*" && rule.second != tiles[b].name) continue
                val first = indexes[a]
                val second = indexes[b]
                val offsets = HashSet<AtomPoint>()
                for (direction in rule.directions) {
                    val step = directions[direction]
                    val faceA = first.keys.filter { it + step !in first }
                    val faceB = second.keys.filter { it - step !in second }
                    for (p in faceA) for (q in faceB) {
                        val offset = p + step - q
                        if (rule.offset == null || rule.offset == offset) offsets += offset
                    }
                }
                for (offset in offsets) {
                    if (second.keys.any { it + offset in first }) continue
                    // One legal relative placement may touch along several faces, especially for concave modules.
                    for ((p, stateA) in first) for ((direction, step) in directions.withIndex()) {
                        second[p + step - offset]?.let { allow(direction, stateA, it) }
                    }
                }
            }
        }
        return TileSet(atomSize.x, atomSize.y, atomSize.z, variants, neighbors)
    }
}
