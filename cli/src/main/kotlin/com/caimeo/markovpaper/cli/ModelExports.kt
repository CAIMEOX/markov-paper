package com.caimeo.markovpaper.cli

import com.caimeo.markovpaper.xml.CompiledMarkovModel
import com.caimeo.markovpaper.xml.GridSize
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import javax.imageio.ImageIO

internal object ModelExports {
    fun format(path: Path): String = path.fileName.toString().substringAfterLast('.', "").lowercase().also {
        require(it in setOf("json", "png", "vox")) { "Output extension must be .json, .png or .vox" }
    }

    fun write(model: CompiledMarkovModel, path: Path, format: String, slice: Int, scale: Int, seed: Long) {
        val grid = model.grid
        Files.newOutputStream(path, CREATE_NEW).use { stream ->
            when (format) {
                "json" -> {
                    val size = GridSize(grid.sizeX, grid.sizeY, grid.sizeZ)
                    val text = "{\"size\":${size.json()},\"symbols\":${model.symbols.joinToString("").json()}," +
                        "\"seed\":$seed,\"order\":\"x-fastest\",\"cells\":${grid.copyState().json()}}\n"
                    stream.write(text.toByteArray())
                }
                "png" -> {
                    val image = BufferedImage(grid.sizeX * scale, grid.sizeY * scale, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until grid.sizeY) for (x in 0 until grid.sizeX) {
                        val color = color(model, grid[x, y, slice].toInt())
                        for (dy in 0 until scale) for (dx in 0 until scale) {
                            image.setRGB(x * scale + dx, (grid.sizeY - 1 - y) * scale + dy, color)
                        }
                    }
                    check(ImageIO.write(image, "png", stream)) { "No PNG writer available" }
                }
                "vox" -> {
                    fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        .apply { values.forEach { putInt(it) } }.array()
                    fun chunk(id: String, body: ByteArray) = id.toByteArray() + ints(body.size, 0) + body
                    val cells = ByteArrayOutputStream()
                    var count = 0
                    for (z in 0 until grid.sizeZ) for (y in 0 until grid.sizeY) for (x in 0 until grid.sizeX) {
                        val value = grid[x, y, z].toInt()
                        if (value == 0) continue
                        cells.write(byteArrayOf(x.toByte(), y.toByte(), z.toByte(), value.toByte()))
                        count++
                    }
                    val palette = ByteArrayOutputStream()
                    for (i in 1..256) {
                        val color = color(model, i)
                        palette.write(byteArrayOf((color shr 16).toByte(), (color shr 8).toByte(), color.toByte(), 0xff.toByte()))
                    }
                    val children = chunk("SIZE", ints(grid.sizeX, grid.sizeY, grid.sizeZ)) +
                        chunk("XYZI", ints(count) + cells.toByteArray()) + chunk("RGBA", palette.toByteArray())
                    stream.write("VOX ".toByteArray() + ints(150) + "MAIN".toByteArray() + ints(0, children.size) + children)
                }
            }
        }
    }

    private fun color(model: CompiledMarkovModel, value: Int): Int {
        if (value == 0) return 0xff20252b.toInt()
        return when (model.symbols.getOrNull(value)?.uppercaseChar()) {
            'W' -> 0xffd9c791.toInt()
            'S' -> 0xffd6c389.toInt()
            'F' -> 0xff9c894f.toInt()
            'L' -> 0xffe8ffff.toInt()
            'R' -> 0xffb65454.toInt()
            'Y' -> 0xffdebb59.toInt()
            'G' -> 0xff7caa6b.toInt()
            else -> Color.HSBtoRGB((value * 0.61803398875 % 1).toFloat(), 0.6f, 0.9f)
        }
    }
}
