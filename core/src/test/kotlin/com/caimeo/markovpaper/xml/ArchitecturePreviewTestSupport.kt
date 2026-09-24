package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.engine.RewriteNode
import com.caimeo.markovpaper.engine.StepDelta

internal fun RewriteNode.firstVisibleFrameWithin(maxSteps: Int): StepDelta? {
    var firstVisible: StepDelta? = null
    repeat(maxSteps) { step ->
        val delta = try {
            advance()
        } catch (exception: Exception) {
            throw AssertionError("architecture preview failed at rewrite ${step + 1}", exception)
        } ?: return firstVisible
        if (firstVisible == null && delta.changes.isNotEmpty()) firstVisible = delta
    }
    return firstVisible
}
