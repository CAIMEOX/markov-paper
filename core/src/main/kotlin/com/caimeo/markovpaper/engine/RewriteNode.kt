package com.caimeo.markovpaper.engine

interface RewriteNode {
    fun advance(): StepDelta?

    fun reset()
}
