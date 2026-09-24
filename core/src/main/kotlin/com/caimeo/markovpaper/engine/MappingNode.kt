package com.caimeo.markovpaper.engine

import java.lang.Math.floorMod

data class Scale(val numerator: Int, val denominator: Int = 1) {
    init {
        require(numerator > 0 && denominator > 0)
    }

    fun apply(value: Int): Int = Math.toIntExact(value.toLong() * numerator / denominator)
}

data class MappingRule(
    val input: IntArray,
    val inputSizeX: Int,
    val inputSizeY: Int,
    val inputSizeZ: Int,
    val output: ByteArray,
    val outputSizeX: Int,
    val outputSizeY: Int,
    val outputSizeZ: Int,
) {
    init {
        require(input.size == inputSizeX * inputSizeY * inputSizeZ)
        require(output.size == outputSizeX * outputSizeY * outputSizeZ)
    }
}

class MappingNode(
    private val source: VoxelGrid,
    private val output: VoxelGrid,
    private val rules: List<MappingRule>,
    private val scaleX: Scale,
    private val scaleY: Scale,
    private val scaleZ: Scale,
) : RewriteNode {
    private var done = false

    override fun advance(): StepDelta? {
        if (done) return null
        done = true
        val changes = LinkedHashMap<Int, CellChange>()

        fun write(x: Int, y: Int, z: Int, value: Byte) {
            if (value == Rule.UNCHANGED) return
            val index = output.index(x, y, z)
            val previous = output[x, y, z]
            if (previous == value) return
            output[x, y, z] = value
            val first = changes[index]
            changes[index] = CellChange(
                index = index,
                x = x,
                y = y,
                z = z,
                before = first?.before ?: previous,
                after = value,
            )
        }

        for (z in 0 until output.sizeZ) {
            for (y in 0 until output.sizeY) {
                for (x in 0 until output.sizeX) write(x, y, z, 0)
            }
        }
        for (rule in rules) {
            for (z in 0 until source.sizeZ) {
                for (y in 0 until source.sizeY) {
                    for (x in 0 until source.sizeX) {
                        if (!matches(rule, x, y, z)) continue
                        val outputOriginX = scaleX.apply(x)
                        val outputOriginY = scaleY.apply(y)
                        val outputOriginZ = scaleZ.apply(z)
                        for (localZ in 0 until rule.outputSizeZ) {
                            for (localY in 0 until rule.outputSizeY) {
                                for (localX in 0 until rule.outputSizeX) {
                                    val value = rule.output[
                                        localX + localY * rule.outputSizeX +
                                            localZ * rule.outputSizeX * rule.outputSizeY
                                    ]
                                    write(
                                        floorMod(outputOriginX + localX, output.sizeX),
                                        floorMod(outputOriginY + localY, output.sizeY),
                                        floorMod(outputOriginZ + localZ, output.sizeZ),
                                        value,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return StepDelta(changes.values.filter { it.before != it.after })
    }

    override fun reset() {
        done = false
    }

    private fun matches(rule: MappingRule, originX: Int, originY: Int, originZ: Int): Boolean {
        for (z in 0 until rule.inputSizeZ) {
            for (y in 0 until rule.inputSizeY) {
                for (x in 0 until rule.inputSizeX) {
                    val mask = rule.input[
                        x + y * rule.inputSizeX + z * rule.inputSizeX * rule.inputSizeY
                    ]
                    val value = source[
                        floorMod(originX + x, source.sizeX),
                        floorMod(originY + y, source.sizeY),
                        floorMod(originZ + z, source.sizeZ),
                    ]
                    if (mask and (1 shl value.toInt()) == 0) return false
                }
            }
        }
        return true
    }
}
