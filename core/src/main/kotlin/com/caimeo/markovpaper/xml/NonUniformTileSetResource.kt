package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.engine.AtomPoint
import com.caimeo.markovpaper.engine.NonUniformNeighbor
import com.caimeo.markovpaper.engine.NonUniformTile
import com.caimeo.markovpaper.engine.NonUniformTiles
import com.caimeo.markovpaper.engine.TileSet
import org.w3c.dom.Element

/** The nonUniform tileset extension. The enclosing <wfc> remains ordinary MJ XML. */
internal object NonUniformTileSetResource {
    fun load(root: Element, symbols: String, resources: ModelResources, folder: String): TileSet {
        val atomSize = point(root.optionalAttribute("atomSize") ?: "1 1 1")
        require(atomSize.x > 0 && atomSize.y > 0 && atomSize.z > 0) { "atomSize must be positive" }
        val atomVolume = Math.multiplyExact(Math.multiplyExact(atomSize.x, atomSize.y), atomSize.z)
        require(atomVolume <= 2_000_000) { "atomSize is too large" }
        val definitions = root.direct("tiles").single().direct("tile")
        require(definitions.map { it.getAttribute("name") }.distinct().size == definitions.size) { "Duplicate NUT name" }
        val tiles = definitions.flatMap { element ->
            val name = element.getAttribute("name")
            require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid NUT name '$name'" }
            val pattern = element.optionalAttribute("voxels")?.let(::readPattern) ?: run {
                val file = element.optionalAttribute("vox") ?: "$name.vox"
                val raw = VoxelResource.readRaw(resources.open("tilesets/$folder/$file"))
                // VOX empty space is explicit owned air unless excluded by the atom mask.
                val legend = element.optionalAttribute("legend") ?: symbols.drop(1)
                VoxelPattern(CharArray(raw.cells.size) { index ->
                    val color = raw.cells[index]
                    if (color == -1) symbols.first() else {
                        require(color in 1..legend.length) { "VOX palette index $color needs a legend in NUT '$name'" }
                        legend[color - 1]
                    }
                }, raw.sizeX, raw.sizeY, raw.sizeZ)
            }
            require(pattern.cells.all { it in symbols }) { "NUT '$name' contains undeclared output symbols" }
            require(pattern.sizeX % atomSize.x == 0 && pattern.sizeY % atomSize.y == 0 &&
                pattern.sizeZ % atomSize.z == 0) { "NUT '$name' dimensions must be multiples of atomSize" }
            val extent = AtomPoint(pattern.sizeX / atomSize.x, pattern.sizeY / atomSize.y, pattern.sizeZ / atomSize.z)
            val mask = element.optionalAttribute("mask")?.let(::readPattern)
            require(mask == null || mask.sizeX == extent.x && mask.sizeY == extent.y && mask.sizeZ == extent.z &&
                mask.cells.all { it == 'X' || it == '.' }) { "NUT '$name' mask must use X/. at atom resolution" }
            val atoms = linkedMapOf<AtomPoint, ByteArray>()
            for (z in 0 until extent.z) for (y in 0 until extent.y) for (x in 0 until extent.x) {
                if (mask != null && mask.cells[x + y * extent.x + z * extent.x * extent.y] == '.') continue
                atoms[AtomPoint(x, y, z)] = ByteArray(atomVolume) { index ->
                    val px = x * atomSize.x + index % atomSize.x
                    val py = y * atomSize.y + index / atomSize.x % atomSize.y
                    val pz = z * atomSize.z + index / (atomSize.x * atomSize.y)
                    symbols.indexOf(pattern.cells[px + py * pattern.sizeX + pz * pattern.sizeX * pattern.sizeY]).toByte()
                }
            }
            require(atoms.isNotEmpty()) { "NUT '$name' mask is empty" }
            val minimum = AtomPoint(atoms.keys.minOf { it.x }, atoms.keys.minOf { it.y }, atoms.keys.minOf { it.z })
            val normalized = atoms.mapKeys { it.key - minimum }
            val rotations = element.optionalAttribute("rotations") ?: "none"
            require(rotations in setOf("none", "z")) { "NUT rotations must be 'none' or 'z'" }
            require(rotations != "z" || atomSize.x == atomSize.y) { "Z rotations require square atom X/Y dimensions" }
            val variants = ArrayList<Map<AtomPoint, ByteArray>>()
            var rotated = normalized
            repeat(if (rotations == "z") 4 else 1) {
                if (variants.none { previous -> previous.keys == rotated.keys &&
                    previous.all { (p, bytes) -> bytes.contentEquals(rotated.getValue(p)) } }) variants += rotated
                if (rotations == "z") {
                    val sizeY = rotated.keys.maxOf { it.y } + 1
                    rotated = rotated.map { (p, bytes) ->
                        AtomPoint(sizeY - 1 - p.y, p.x, p.z) to ByteArray(atomVolume) { index ->
                            val x = index % atomSize.x
                            val y = index / atomSize.x % atomSize.y
                            val z = index / (atomSize.x * atomSize.y)
                            bytes[y + (atomSize.y - 1 - x) * atomSize.x + z * atomSize.x * atomSize.y]
                        }
                    }.toMap()
                }
            }
            val weight = element.doubleAttribute("weight", 1.0)
            require(weight.isFinite() && weight > 0) { "NUT '$name' weight must be positive and finite" }
            variants.map { NonUniformTile(name, it, weight / variants.size) }
        }
        val rules = root.direct("neighbors").single().direct("neighbor").map { element ->
            val horizontal = element.hasAttribute("left")
            val first = element.getAttribute(if (horizontal) "left" else "bottom")
            val second = element.getAttribute(if (horizontal) "right" else "top")
            val directions = (element.optionalAttribute("directions") ?: if (horizontal) "x y" else "z")
                .trim().split(Regex("\\s+")).map { direction ->
                    when (direction) {
                        "x", "+x" -> 0
                        "y", "+y" -> 1
                        "-x" -> 2
                        "-y" -> 3
                        "z", "+z" -> 4
                        "-z" -> 5
                        else -> error("Unknown NUT adjacency direction '$direction'")
                    }
                }
            NonUniformNeighbor(first, second, directions, element.optionalAttribute("offset")?.let(::point))
        }
        return NonUniformTiles.compile(atomSize, tiles, rules)
    }

    private fun point(text: String): AtomPoint {
        val parts = text.trim().split(Regex("\\s+")).map(String::toInt)
        require(parts.size == 3) { "Expected three coordinates in '$text'" }
        return AtomPoint(parts[0], parts[1], parts[2])
    }

    private fun readPattern(text: String): VoxelPattern {
        val layers = text.trim().split(Regex("\\s+")).map { it.split('/') }
        val x = layers.first().first().length
        val y = layers.first().size
        require(x > 0 && layers.all { layer -> layer.size == y && layer.all { it.length == x } }) {
            "NUT patterns must have rectangular rows and equally sized layers"
        }
        require(x.toLong() * y * layers.size <= 2_000_000) { "NUT pattern is too large" }
        return VoxelPattern(layers.flatMap { it.flatMap(String::toList) }.toCharArray(), x, y, layers.size)
    }
}

private fun Element.direct(tag: String): List<Element> = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

private fun Element.optionalAttribute(name: String): String? = getAttribute(name).takeIf(String::isNotEmpty)

private fun Element.doubleAttribute(name: String, default: Double): Double = optionalAttribute(name)?.toDouble() ?: default
