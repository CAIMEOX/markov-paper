package com.caimeo.markovpaper.xml

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VoxelPattern(
    val cells: CharArray,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
)

data class RawVoxelPattern(
    val cells: IntArray,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
)

object VoxelResource {
    fun read(input: InputStream, legend: String): VoxelPattern {
        val raw = readRaw(input)
        val colors = raw.cells
        val uniqueColors = ArrayList<Int>()
        val cells = CharArray(colors.size) { index ->
            val color = colors[index]
            var ordinal = uniqueColors.indexOf(color)
            if (ordinal < 0) {
                ordinal = uniqueColors.size
                uniqueColors += color
            }
            require(ordinal < legend.length) {
                "VOX file uses more colors than legend '$legend'"
            }
            legend[ordinal]
        }
        return VoxelPattern(cells, raw.sizeX, raw.sizeY, raw.sizeZ)
    }

    fun readRaw(input: InputStream): RawVoxelPattern {
        val bytes = input.use { it.readNBytes(MAX_FILE_BYTES + 1) }
        require(bytes.size <= MAX_FILE_BYTES) { "VOX resource exceeds 64 MiB" }
        require(bytes.size >= 8 && bytes.copyOfRange(0, 4).decodeToString() == "VOX ") {
            "Not a MagicaVoxel VOX file"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sizeX = -1
        var sizeY = -1
        var sizeZ = -1
        var cells: IntArray? = null

        fun parseChunks(start: Int, end: Int, depth: Int = 0) {
            require(depth < 32) { "VOX chunks are nested too deeply" }
            var position = start
            while (position + 12 <= end) {
                val id = bytes.copyOfRange(position, position + 4).decodeToString()
                val contentSize = buffer.getInt(position + 4)
                val childrenSize = buffer.getInt(position + 8)
                val contentStart = position + 12
                require(contentSize >= 0 && childrenSize >= 0 &&
                    contentStart.toLong() + contentSize + childrenSize <= end) { "Invalid VOX chunk '$id'" }
                val contentEnd = contentStart + contentSize
                val childrenEnd = contentEnd + childrenSize
                when (id) {
                    "SIZE" -> {
                        require(contentSize >= 12 && cells == null) { "Expected a single-model VOX resource" }
                        sizeX = buffer.getInt(contentStart)
                        sizeY = buffer.getInt(contentStart + 4)
                        sizeZ = buffer.getInt(contentStart + 8)
                        require(sizeX in 1..256 && sizeY in 1..256 && sizeZ in 1..256 &&
                            sizeX.toLong() * sizeY * sizeZ <= 2_000_000L) { "Invalid or oversized VOX dimensions" }
                    }
                    "XYZI" -> {
                        require(sizeX > 0 && sizeY > 0 && sizeZ > 0 && contentSize >= 4 && cells == null)
                        val count = buffer.getInt(contentStart)
                        require(count >= 0 && 4L + count.toLong() * 4 <= contentSize) { "Truncated VOX voxel data" }
                        val result = IntArray(sizeX * sizeY * sizeZ) { -1 }
                        for (index in 0 until count) {
                            val offset = contentStart + 4 + index * 4
                            val x = bytes[offset].toUByte().toInt()
                            val y = bytes[offset + 1].toUByte().toInt()
                            val z = bytes[offset + 2].toUByte().toInt()
                            val color = bytes[offset + 3].toUByte().toInt()
                            require(x < sizeX && y < sizeY && z < sizeZ) { "VOX coordinate outside SIZE" }
                            result[x + y * sizeX + z * sizeX * sizeY] = color
                        }
                        cells = result
                    }
                }
                if (childrenSize > 0) parseChunks(contentEnd, childrenEnd, depth + 1)
                position = childrenEnd
            }
            require(position == end) { "Truncated VOX chunk header" }
        }

        parseChunks(8, bytes.size)
        return RawVoxelPattern(
            cells = requireNotNull(cells) { "VOX file has no XYZI chunk" },
            sizeX = sizeX,
            sizeY = sizeY,
            sizeZ = sizeZ,
        )
    }

    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
}
