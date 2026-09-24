package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.assemblage.AssemblageTrace
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.TraceSource
import com.caimeo.markovpaper.assemblage.Vec3i

internal data class SceneStateChange(
    val position: Vec3i,
    val before: BlockStateSpec?,
    val after: BlockStateSpec?,
)

internal data class SceneAnimationStage(
    val introducedSource: TraceSource,
    val changes: List<SceneStateChange>,
)

internal data class SceneTraceAnimationPlan(
    val initial: SceneSnapshot,
    val stages: List<SceneAnimationStage>,
    val finalScene: SceneSnapshot,
)

internal sealed interface SceneTracePlaybackEvent {
    data class Changes(
        val stageIndex: Int,
        val stage: SceneAnimationStage,
        val changes: List<SceneStateChange>,
        val stageStarted: Boolean,
    ) : SceneTracePlaybackEvent

    data class Complete(val scene: SceneSnapshot) : SceneTracePlaybackEvent
}

internal class SceneTracePlayback(
    private val plan: SceneTraceAnimationPlan,
    private val changesPerTick: Int,
) {
    private var stageIndex = 0
    private var changeIndex = 0
    private var complete = false

    init {
        require(changesPerTick > 0) { "Trace animation speed must be positive" }
    }

    fun advance(): SceneTracePlaybackEvent {
        check(!complete) { "Trace playback is already complete" }
        if (stageIndex >= plan.stages.size) {
            complete = true
            return SceneTracePlaybackEvent.Complete(plan.finalScene)
        }
        val currentStageIndex = stageIndex
        val stage = plan.stages[currentStageIndex]
        val stageStarted = changeIndex == 0
        val end = minOf(changeIndex + changesPerTick, stage.changes.size)
        val changes = stage.changes.subList(changeIndex, end).toList()
        changeIndex = end
        if (changeIndex >= stage.changes.size) {
            stageIndex++
            changeIndex = 0
        }
        return SceneTracePlaybackEvent.Changes(
            stageIndex = currentStageIndex,
            stage = stage,
            changes = changes,
            stageStarted = stageStarted,
        )
    }
}

internal fun sceneTraceAnimationPlan(trace: AssemblageTrace): SceneTraceAnimationPlan {
    val stages = trace.frames.zipWithNext { before, after ->
        val positions = (before.scene.cells.keys + after.scene.cells.keys)
            .distinct()
            .sortedWith(compareBy(Vec3i::y, Vec3i::x, Vec3i::z))
        SceneAnimationStage(
            introducedSource = after.introducedSource,
            changes = positions.mapNotNull { position ->
                val beforeState = before.scene.cells[position]?.state
                val afterState = after.scene.cells[position]?.state
                if (beforeState == afterState) null
                else SceneStateChange(position, beforeState, afterState)
            },
        )
    }
    return SceneTraceAnimationPlan(
        initial = trace.frames.first().scene,
        stages = stages,
        finalScene = trace.finalScene,
    )
}
