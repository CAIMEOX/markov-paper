package com.caimeo.markovpaper.paper

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockCoordinateTest {
    @Test
    fun `setpos coordinates support absolute and relative values`() {
        assertEquals(42, parseBlockCoordinate("42", base = 100))
        assertEquals(100, parseBlockCoordinate("~", base = 100))
        assertEquals(105, parseBlockCoordinate("~5", base = 100))
        assertEquals(95, parseBlockCoordinate("~-5", base = 100))
    }
}
