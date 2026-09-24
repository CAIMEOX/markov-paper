package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertTrue

class BundledModelsTest {
    @Test
    fun `bundled markov junior models compile and execute`() {
        val names = listOf(
            "maze",
            "backtracker",
            "backtracker-cycle",
            "dungeon",
            "snake",
            "cave3d",
            "life2d",
            "growth3d",
            "no-dead-ends3d",
            "river3d",
            "counting3d",
            "maze-trail3d",
            "noise3d",
            "stairs-path-rules3d",
            "cross-country2d",
            "nystrom-dungeon2d",
            "dungeon-growth2d",
        )

        for (name in names) {
            val resource = requireNotNull(javaClass.getResource("/models/$name.xml")) {
                "Missing bundled model $name"
            }
            val model = MarkovXmlCompiler.compile(
                xml = resource.readText(),
                sizeX = 9,
                sizeY = 9,
                sizeZ = if (name.endsWith("2d")) 1 else 9,
                seed = 17,
            )
            var changes = 0
            while (changes < 40 && model.node.advance() != null) changes++
            assertTrue(changes > 0, "$name terminated without producing a frame")
        }
    }
}
