package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileSetResourceTest {
    @Test
    fun `marching hills loads rotated voxel variants and complete adjacency`() {
        val tileSet = TileSetResource.load(
            name = "MarchingHills",
            outputSymbols = "BaV OoYhSAWwRrlgvPpxbUuCcKkXGEFi".filterNot(Char::isWhitespace),
        )

        assertEquals(3, tileSet.sizeX)
        assertEquals(setOf("Empty", "Stone", "Wall", "In", "Out"), tileSet.variants.map { it.name }.toSet())
        assertTrue(tileSet.neighbors.all { direction ->
            direction.all { allowed -> allowed.any { it } }
        })
    }
}
