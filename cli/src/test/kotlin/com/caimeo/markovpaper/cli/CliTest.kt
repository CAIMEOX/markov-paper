package com.caimeo.markovpaper.cli

import com.caimeo.markovpaper.xml.MarkovXmlCompiler
import com.caimeo.markovpaper.xml.VoxelResource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class CliTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `CLI and direct core execution agree and exported animation reconstructs the final grid`() {
        val output = directory.resolve("result.json")
        val trace = directory.resolve("trace.jsonl")
        val log = ByteArrayOutputStream()
        assertEquals(0, runCli(arrayOf("run", "nut-bricks", "--size", "5", "4", "3", "--seed", "29",
            "--output", output.toString(), "--trace", trace.toString()), PrintStream(log), PrintStream(log)), log.toString())
        val xml = requireNotNull(javaClass.getResourceAsStream("/models/nut-bricks.xml")).bufferedReader().use { it.readText() }
        val direct = MarkovXmlCompiler.compile(xml, 5, 4, 3, 29)
        while (direct.node.advance() != null) Unit
        fun cells(json: String) = json.substringAfter("\"cells\":").substringAfter('[').substringBefore(']')
            .split(',').map { it.trim().toByte() }.toByteArray()
        val final = cells(Files.readString(output))
        assertContentEquals(direct.grid.copyState(), final)
        val lines = Files.readAllLines(trace)
        val replay = cells(lines.first())
        val changePattern = Regex("\\[(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)\\]")
        var frames = 0
        for (line in lines.filter { "\"event\":\"frame\"" in it }) {
            frames++
            for (match in changePattern.findAll(line)) {
                val (x, y, z, before, after) = match.groupValues.drop(1).map(String::toInt)
                val index = x + y * 5 + z * 5 * 4
                assertEquals(before.toByte(), replay[index])
                replay[index] = after.toByte()
            }
        }
        assertTrue(frames > 1)
        assertTrue("\"complete\":true" in lines.last())
        assertContentEquals(final, replay)
    }

    @Test
    fun `compile reports expanded bounds and generated vox and png are independently readable`() {
        val xml = directory.resolve("model.xml")
        Files.writeString(xml, """<map values="BS" scale="2 1 2"><rule in="B" out="SS SS"/></map>""")
        val log = ByteArrayOutputStream()
        assertEquals(0, runCli(arrayOf("compile", xml.toString(), "--size", "2", "3", "1"),
            PrintStream(log), PrintStream(log)))
        assertTrue("\"size\":[4,3,2]" in log.toString())
        for (extension in listOf("vox", "png")) {
            val output = directory.resolve("result.$extension")
            assertEquals(0, runCli(arrayOf("run", xml.toString(), "--size", "2", "3", "1", "--output", output.toString()),
                PrintStream(log), PrintStream(log)), log.toString())
        }
        val vox = VoxelResource.readRaw(Files.newInputStream(directory.resolve("result.vox")))
        assertEquals(4, vox.sizeX)
        assertEquals(3, vox.sizeY)
        assertEquals(2, vox.sizeZ)
        assertTrue(vox.cells.all { it == 1 })
        // A CLI-exported prefab can be reimported through the same external NUT resolver.
        Files.createDirectories(directory.resolve("tilesets/Imported"))
        Files.copy(directory.resolve("result.vox"), directory.resolve("tilesets/Imported/room.vox"))
        Files.writeString(directory.resolve("tilesets/Imported.xml"), """
            <tileset nonUniform="True" atomSize="2 1 1"><tiles>
              <tile name="Room" vox="room.vox" legend="S"/>
            </tiles><neighbors/></tileset>
        """.trimIndent())
        val imported = MarkovXmlCompiler.compile("""<wfc values="BS" tileset="Imported"/>""", 2, 3, 2, 0,
            com.caimeo.markovpaper.xml.ModelResources(directory))
        while (imported.node.advance() != null) Unit
        assertTrue(imported.grid.copyState().all { it == 1.toByte() })
        assertEquals(4, imported.grid.sizeX)
        val png = ImageIO.read(directory.resolve("result.png").toFile())
        assertEquals(4, png.width)
        assertEquals(3, png.height)
    }

    @Test
    fun `budget exhaustion is explicit and an existing output is never overwritten`() {
        val xml = directory.resolve("model.xml")
        Files.writeString(xml, """<one values="BW" in="B" out="W"/>""")
        val output = directory.resolve("result.json")
        val trace = directory.resolve("trace.jsonl")
        val log = ByteArrayOutputStream()
        val args = arrayOf("run", xml.toString(), "--size", "10", "1", "1", "--max-steps", "1",
            "--output", output.toString(), "--trace", trace.toString())
        assertEquals(3, runCli(args, PrintStream(log), PrintStream(log)))
        assertFalse(Files.exists(output))
        assertTrue("\"complete\":false" in Files.readAllLines(trace).last())
        Files.writeString(output, "keep me")
        assertEquals(1, runCli(args, PrintStream(log), PrintStream(log)))
        assertEquals("keep me", Files.readString(output))
    }
}
