package com.caimeo.markovpaper.scene

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.Vec3i

interface SceneProgram {
    val initial: SceneSnapshot
    val current: SceneSnapshot
    val extent: Extent3i
        get() = current.size

    fun advance(): SceneProgramDelta?
}

data class SceneProgramChange(
    val position: Vec3i,
    val before: BlockStateSpec?,
    val after: BlockStateSpec?,
)

data class SceneProgramDelta(
    val phase: String?,
    val changes: List<SceneProgramChange>,
)
