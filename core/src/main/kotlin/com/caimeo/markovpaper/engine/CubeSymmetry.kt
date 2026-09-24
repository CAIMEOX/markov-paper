package com.caimeo.markovpaper.engine

object CubeSymmetry {
    fun rotations(rule: Rule): List<Rule> = properRotations
        .map { rule.transformed(it.permutation, it.signs) }
        .distinctBy(Rule::structuralKey)

    fun all(rule: Rule): List<Rule> = allTransforms
        .map { rule.transformed(it.permutation, it.signs) }
        .distinctBy(Rule::structuralKey)

    fun expand(rule: Rule, is3D: Boolean, group: String?): List<Rule> {
        return transforms(is3D, group)
            .map { rule.transformed(it.permutation, it.signs) }
            .distinctBy(Rule::structuralKey)
    }

    internal fun transforms(is3D: Boolean, group: String?): List<AxisTransform> =
        if (is3D) cubeGroup(group) else squareGroup(group)

    private val axisPermutations = listOf(
        intArrayOf(0, 1, 2),
        intArrayOf(0, 2, 1),
        intArrayOf(1, 0, 2),
        intArrayOf(1, 2, 0),
        intArrayOf(2, 0, 1),
        intArrayOf(2, 1, 0),
    )

    private val allTransforms: List<AxisTransform> = buildList {
        for (permutation in axisPermutations) {
            for (xSign in listOf(-1, 1)) {
                for (ySign in listOf(-1, 1)) {
                    for (zSign in listOf(-1, 1)) {
                        val signs = intArrayOf(xSign, ySign, zSign)
                        add(AxisTransform(permutation, signs))
                    }
                }
            }
        }
    }

    private val properRotations = allTransforms.filter {
        permutationSign(it.permutation) * it.signs.reduce(Int::times) == 1
    }

    private val identity = AxisTransform(
        permutation = intArrayOf(0, 1, 2),
        signs = intArrayOf(1, 1, 1),
    )

    private val squareTransforms = allTransforms.filter {
        it.permutation[2] == 2 && it.signs[2] == 1
    }

    private fun cubeGroup(group: String?): List<AxisTransform> = when (group) {
        null, "(xyz)" -> allTransforms
        "(xyz+)" -> properRotations
        "()" -> listOf(identity)
        "(x)" -> axisReflections(axis = 0)
        "(z)" -> axisReflections(axis = 2)
        "(xy)" -> squareTransforms
        else -> error("Unsupported 3D symmetry '$group'")
    }

    private fun squareGroup(group: String?): List<AxisTransform> = when (group) {
        null, "(xy)" -> squareTransforms
        "()" -> listOf(identity)
        "(x)" -> axisReflections(axis = 0)
        "(y)" -> axisReflections(axis = 1)
        "(x)(y)" -> squareTransforms.filter { it.permutation.contentEquals(identity.permutation) }
        "(xy+)" -> squareTransforms.filter {
            permutationSign(it.permutation) * it.signs[0] * it.signs[1] == 1
        }
        else -> error("Unsupported 2D symmetry '$group'")
    }

    private fun axisReflections(axis: Int): List<AxisTransform> = listOf(
        identity,
        AxisTransform(
            permutation = identity.permutation.copyOf(),
            signs = IntArray(3) { if (it == axis) -1 else 1 },
        ),
    )

    private fun permutationSign(permutation: IntArray): Int {
        var inversions = 0
        for (left in permutation.indices) {
            for (right in left + 1 until permutation.size) {
                if (permutation[left] > permutation[right]) inversions++
            }
        }
        return if (inversions % 2 == 0) 1 else -1
    }

    internal data class AxisTransform(
        val permutation: IntArray,
        val signs: IntArray,
    )
}
