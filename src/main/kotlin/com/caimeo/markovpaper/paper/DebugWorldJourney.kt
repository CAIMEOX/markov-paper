package com.caimeo.markovpaper.paper

import java.util.UUID

data class DebugReturnPoint(
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

class DebugWorldJourney {
    private val returnPoints = HashMap<UUID, DebugReturnPoint>()

    fun remember(playerId: UUID, point: DebugReturnPoint) {
        returnPoints[playerId] = point
    }

    fun take(playerId: UUID): DebugReturnPoint? = returnPoints.remove(playerId)

    fun clear() {
        returnPoints.clear()
    }
}
