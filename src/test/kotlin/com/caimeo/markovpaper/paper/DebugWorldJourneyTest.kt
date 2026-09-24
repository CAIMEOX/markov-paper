package com.caimeo.markovpaper.paper

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DebugWorldJourneyTest {
    @Test
    fun `exit consumes the most recent non-debug return point`() {
        val playerId = UUID.randomUUID()
        val overworldId = UUID.randomUUID()
        val journey = DebugWorldJourney()
        val first = DebugReturnPoint(overworldId, 10.5, 70.0, -4.5, 90f, 0f)
        val latest = DebugReturnPoint(overworldId, 50.5, 80.0, 30.5, 180f, 10f)

        journey.remember(playerId, first)
        journey.remember(playerId, latest)

        assertEquals(latest, journey.take(playerId))
        assertNull(journey.take(playerId))
    }
}
