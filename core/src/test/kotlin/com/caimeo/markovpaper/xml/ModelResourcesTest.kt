package com.caimeo.markovpaper.xml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelResourcesTest {
    @TempDir lateinit var folder: Path

    @Test
    fun `external weighted tileset is hot reloaded and drives wfc postprocessing`() {
        val tiles = folder.resolve("tilesets/Custom/vox")
        Files.createDirectories(tiles)
        Files.write(tiles.resolve("Empty.vox"), voxel(null))
        Files.write(tiles.resolve("Solid.vox"), voxel(7))
        val manifest = folder.resolve("tilesets/Custom.xml")
        val xml = """
            <tileset><tiles><tile name="Empty"/><tile name="Solid" weight="7.5"/></tiles>
              <neighbors>
                <neighbor left="Empty" right="Empty"/><neighbor left="Empty" right="Solid"/>
                <neighbor left="Solid" right="Solid"/>
                <neighbor top="Empty" bottom="Empty"/><neighbor top="Solid" bottom="Empty"/>
                <neighbor top="Empty" bottom="Solid"/><neighbor top="Solid" bottom="Solid"/>
              </neighbors>
            </tileset>
        """.trimIndent()
        Files.writeString(manifest, xml)
        val resources = ModelResources(folder)
        assertEquals(7.5, TileSetResource.load("Custom", "BX", resources, "Custom/vox").variants.last().weight)
        val model = MarkovXmlCompiler.compile("""
            <sequence values="BE" origin="True">
              <wfc tileset="Custom" tiles="Custom/vox" values="BXY" tries="12">
                <rule in="B" out="Empty"/><rule in="E" out="Solid"/>
                <one in="X" out="Y"/>
              </wfc>
            </sequence>
        """.trimIndent(), 3, 1, 1, 4, resources)
        repeat(30) { model.node.advance() ?: return@repeat }
        assertContentEquals(byteArrayOf(0, 2, 0), model.grid.copyState())
        Files.writeString(manifest, xml.replace("7.5", "0.5"))
        assertEquals(0.5, TileSetResource.load("Custom", "BX", resources, "Custom/vox").variants.last().weight)
        assertTrue(TileSetResource.load("Partition", "BVL", resources).variants.isNotEmpty())
    }

    @Test
    fun `data folder overrides a bundled resource without hiding unrelated bundled files`() {
        Files.createDirectories(folder.resolve("tilesets"))
        val resources = ModelResources(folder)
        val original = resources.open("tilesets/Partition.xml").bufferedReader().use { it.readText() }
        Files.writeString(folder.resolve("tilesets/Partition.xml"), original.replace("2.0", "9.0"))
        val set = TileSetResource.load("Partition", "BVL", resources)
        assertEquals(9.0, set.variants.first { it.name == "Nothing" }.weight)
    }

    @Test
    fun `tiles with identical geometry retain independent constraint identities`() {
        val tiles = folder.resolve("tilesets/Twins")
        Files.createDirectories(tiles)
        Files.write(tiles.resolve("A.vox"), voxel(null))
        Files.write(tiles.resolve("B.vox"), voxel(null))
        Files.writeString(folder.resolve("tilesets/Twins.xml"), """
            <tileset><tiles><tile name="A"/><tile name="B" weight="3"/></tiles>
              <neighbors><neighbor left="A" right="B"/><neighbor top="A" bottom="B"/></neighbors>
            </tileset>
        """.trimIndent())
        val set = TileSetResource.load("Twins", "B", ModelResources(folder))
        assertEquals(2, set.variants.size)
        assertTrue(set.neighbors[0][0][1])
        assertTrue(set.neighbors[0][1][0])
        assertTrue(!set.neighbors[0][0][0])
    }

    @Test
    fun `resolver rejects traversal and symlinks outside its directory`() {
        val root = folder.resolve("data")
        Files.createDirectory(root)
        val outside = folder.resolve("secret.vox")
        Files.write(outside, byteArrayOf(1))
        Files.createSymbolicLink(root.resolve("escape.vox"), outside)
        val resources = ModelResources(root)
        assertFailsWith<IllegalArgumentException> { resources.open("../secret.vox") }
        assertFailsWith<IllegalArgumentException> { resources.open("/etc/passwd") }
        assertFailsWith<IllegalArgumentException> { resources.open("escape.vox") }
    }

    private fun voxel(color: Int?): ByteArray {
        val count = if (color == null) 0 else 1
        return ByteBuffer.allocate(60 + 4 * count).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("VOX ".toByteArray()); putInt(150)
            put("MAIN".toByteArray()); putInt(0); putInt(40 + 4 * count)
            put("SIZE".toByteArray()); putInt(12); putInt(0); putInt(1); putInt(1); putInt(1)
            put("XYZI".toByteArray()); putInt(4 + 4 * count); putInt(0); putInt(count)
            if (color != null) put(byteArrayOf(0, 0, 0, color.toByte()))
        }.array()
    }

    @Test
    fun `malformed external voxel dimensions fail before allocation`() {
        val bytes = voxel(7)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32, Int.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { VoxelResource.readRaw(bytes.inputStream()) }
        assertFailsWith<IllegalArgumentException> { VoxelResource.readRaw(voxel(7).copyOf(62).inputStream()) }
    }
}
