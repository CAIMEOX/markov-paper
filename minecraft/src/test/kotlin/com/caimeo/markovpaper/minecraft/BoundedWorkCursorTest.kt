package com.caimeo.markovpaper.minecraft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class BoundedWorkCursorTest {
    @Test
    fun `failed or partially acknowledged batches retain their uncommitted prefix`() {
        val cursor = BoundedWorkCursor((0 until 5).iterator())
        assertFailsWith<IllegalStateException> {
            cursor.advance(3) { error("transport failed") }
        }
        val received = mutableListOf<Int>()
        cursor.advance(2) { batch ->
            assertEquals(listOf(0, 1), batch)
            received += batch.first()
            1
        }
        while (!cursor.complete) cursor.advance(2) { batch -> received += batch; batch.size }
        assertEquals((0 until 5).toList(), received)
    }

    @Test
    fun `large restoration input is consumed in bounded ordered batches`() {
        val cursor = BoundedWorkCursor((0 until 10_000).iterator())
        val batches = buildList {
            while (!cursor.complete) cursor.advance(512) { batch -> add(batch); batch.size }
        }

        assertTrue(batches.all { it.size <= 512 })
        assertEquals((0 until 10_000).toList(), batches.flatten())
        assertTrue(cursor.complete)
    }
}
