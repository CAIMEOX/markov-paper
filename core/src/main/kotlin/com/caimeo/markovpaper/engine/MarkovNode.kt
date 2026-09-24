package com.caimeo.markovpaper.engine

class MarkovNode(private val children: List<RewriteNode>) : RewriteNode {
    override fun advance(): StepDelta? {
        for (child in children) {
            val delta = child.advance()
            if (delta != null) return delta
        }

        reset()
        return null
    }

    override fun reset() {
        children.forEach(RewriteNode::reset)
    }
}
