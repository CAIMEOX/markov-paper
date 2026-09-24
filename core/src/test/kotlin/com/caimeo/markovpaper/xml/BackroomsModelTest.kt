package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackroomsModelTest {
    @Test
    fun `backrooms rule set terminates and consumes its growth heads`() {
        val run = completedBackrooms(seed = 2405)
        val model = run.model

        assertTrue(run.frames > 1, "backrooms2d did not animate")
        val state = model.grid.copyState()
        assertEquals(0, state.count { it == model.valueOf('H') })
        assertEquals(0, state.count { it == model.valueOf('V') })
        assertEquals(0, state.count { it == model.valueOf('R') })
        assertEquals(0, state.count { it == model.valueOf('Q') })
    }

    @Test
    fun `door gaps keep the backrooms floor plan connected`() {
        for (seed in VISUAL_SEEDS) {
            assertConnected(completedBackrooms(seed).model, SIZE, seed, requireDoor = true)
        }
    }

    @Test
    fun `thick wall bands do not strand floor cells`() {
        val seed = 1046L

        assertConnected(completedBackrooms(seed).model, SIZE, seed, requireDoor = false)
    }

    @Test
    fun `connectivity repair retains a majority of the generated rooms`() {
        val model = completedBackrooms(seed = 2405).model
        val state = model.grid.copyState()
        val walkableValues = setOf(model.valueOf('F'), model.valueOf('D'), model.valueOf('L'))
        val walkableCount = state.count { it in walkableValues }

        assertTrue(
            walkableCount * 100 >= state.size * 55,
            "backrooms2d retained only $walkableCount/${state.size} walkable cells",
        )
    }

    private fun assertConnected(
        model: CompiledMarkovModel,
        size: Int,
        seed: Long,
        requireDoor: Boolean,
    ) {
        val state = model.grid.copyState()
        val floor = model.valueOf('F')
        val door = model.valueOf('D')
        val light = model.valueOf('L')
        val walkable = state.indices
            .filterTo(HashSet()) { state[it] == floor || state[it] == door || state[it] == light }

        if (requireDoor) {
            assertTrue(state.count { it == door } > 0, "backrooms2d seed $seed generated no door gaps")
        }
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        queue += walkable.first()
        visited += walkable.first()
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % size
            val y = index / size
            for ((nextX, nextY) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nextX !in 0 until size || nextY !in 0 until size) continue
                val next = nextX + nextY * size
                if (next !in visited && next in walkable) {
                    visited += next
                    queue += next
                }
            }
        }

        assertEquals(
            walkable.size,
            visited.size,
            "backrooms2d seed $seed split into disconnected floor regions",
        )
    }

    @Test
    fun `backrooms contains open rooms and sparse fluorescent landmarks`() {
        for (seed in VISUAL_SEEDS) {
            val model = completedBackrooms(seed).model
            val state = model.grid.copyState()
            val light = model.valueOf('L')
            val walkableValues = setOf(model.valueOf('F'), model.valueOf('D'), light)
            val lightCount = state.count { it == light }
            assertTrue(
                lightCount in 2..state.size / 20,
                "backrooms2d seed $seed needs sparse fluorescent landmarks",
            )
            val hasOpenRoom = (0..SIZE - 5).any { originY ->
                (0..SIZE - 5).any { originX ->
                    (0 until 5).all { offsetY ->
                        (0 until 5).all { offsetX ->
                            state[originX + offsetX + (originY + offsetY) * SIZE] in walkableValues
                        }
                    }
                }
            }
            assertTrue(hasOpenRoom, "backrooms2d seed $seed collapsed into maze-width corridors")
        }
    }

    @Test
    fun `backrooms partitions the plane with dense walls`() {
        for (seed in VISUAL_SEEDS) {
            val model = completedBackrooms(seed).model
            val state = model.grid.copyState()
            val wallCount = state.count { it == model.valueOf('W') }

            assertTrue(
                wallCount in state.size / 4..state.size * 45 / 100,
                "backrooms2d seed $seed walls should occupy 25% to 45% of the plane, " +
                    "found $wallCount/${state.size}",
            )
        }
    }

    @Test
    fun `backrooms wall skeleton contains frequent turns and junctions`() {
        for (seed in VISUAL_SEEDS) {
            val model = completedBackrooms(seed).model
            val wall = model.valueOf('W')
            val state = model.grid.copyState()
            val wallCount = state.count { it == wall }
            val turnCount = (0 until SIZE).sumOf { y ->
                (0 until SIZE).count { x ->
                    if (state[x + y * SIZE] != wall) return@count false
                    val horizontal =
                        x > 0 && state[x - 1 + y * SIZE] == wall ||
                            x + 1 < SIZE && state[x + 1 + y * SIZE] == wall
                    val vertical =
                        y > 0 && state[x + (y - 1) * SIZE] == wall ||
                            y + 1 < SIZE && state[x + (y + 1) * SIZE] == wall
                    horizontal && vertical
                }
            }

            assertTrue(
                turnCount * 100 >= wallCount * 20,
                "backrooms2d seed $seed has too few wall turns: $turnCount/$wallCount",
            )
        }
    }

    @Test
    fun `backrooms walls remain a one-cell boundary network`() {
        for (seed in VISUAL_SEEDS) {
            val model = completedBackrooms(seed).model
            val state = model.grid.copyState()
            val wall = model.valueOf('W')
            val solidBlocks = (0 until SIZE - 1).sumOf { y ->
                (0 until SIZE - 1).count { x ->
                    state[x + y * SIZE] == wall &&
                        state[x + 1 + y * SIZE] == wall &&
                        state[x + (y + 1) * SIZE] == wall &&
                        state[x + 1 + (y + 1) * SIZE] == wall
                }
            }

            assertEquals(
                0,
                solidBlocks,
                "backrooms2d seed $seed contains $solidBlocks solid 2x2 wall blocks",
            )
        }
    }

    private fun compileBackrooms(seed: Long, size: Int = SIZE): CompiledMarkovModel {
        val resource = requireNotNull(javaClass.getResource("/models/backrooms2d.xml")) {
            "Missing bundled model backrooms2d"
        }
        return MarkovXmlCompiler.compile(
            xml = resource.readText(),
            sizeX = size,
            sizeY = size,
            sizeZ = 1,
            seed = seed,
        )
    }

    private fun completedBackrooms(seed: Long, size: Int = SIZE): CompletedRun {
        val model = compileBackrooms(seed, size)
        var frames = 0
        while (frames < MAX_FRAMES) {
            if (model.node.advance() == null) return CompletedRun(model, frames)
            frames++
        }
        error("backrooms2d did not terminate within $MAX_FRAMES frames")
    }

    private data class CompletedRun(
        val model: CompiledMarkovModel,
        val frames: Int,
    )

    private companion object {
        const val SIZE = 41
        const val MAX_FRAMES = 5_000
        val VISUAL_SEEDS = listOf(17L, 2405L, 8191L)
    }
}
