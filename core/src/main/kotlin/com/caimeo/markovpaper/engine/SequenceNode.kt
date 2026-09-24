package com.caimeo.markovpaper.engine

class SequenceNode(private val children: List<RewriteNode>) : RewriteNode {
    private var childIndex = 0

    override fun advance(): StepDelta? {
        while (childIndex < children.size) {
            val delta = children[childIndex].advance()
            if (delta != null) return delta
            childIndex++
        }

        reset()
        return null
    }

    override fun reset() {
        children.forEach(RewriteNode::reset)
        childIndex = 0
    }
}
