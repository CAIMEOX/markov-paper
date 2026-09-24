package com.caimeo.markovpaper.engine

class Rule private constructor(
    private val input: IntArray,
    private val output: ByteArray,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val probability: Double = 1.0,
) {
    constructor(
        input: ByteArray,
        output: ByteArray,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        probability: Double = 1.0,
    ) : this(
        input = exactMasks(input),
        output = output.copyOf(),
        sizeX = sizeX,
        sizeY = sizeY,
        sizeZ = sizeZ,
        probability = probability,
    )

    init {
        require(input.size == sizeX * sizeY * sizeZ)
        require(output.size == input.size)
        require(probability in 0.0..1.0)
    }

    fun matches(grid: VoxelGrid, originX: Int, originY: Int, originZ: Int): Boolean {
        for (z in 0 until sizeZ) {
            for (y in 0 until sizeY) {
                for (x in 0 until sizeX) {
                    val patternIndex = x + y * sizeX + z * sizeX * sizeY
                    val value = grid[originX + x, originY + y, originZ + z].toInt()
                    if (input[patternIndex] and (1 shl value) == 0) {
                        return false
                    }
                }
            }
        }
        return true
    }

    fun apply(grid: VoxelGrid, originX: Int, originY: Int, originZ: Int): StepDelta {
        val changes = ArrayList<CellChange>()
        for (write in writes(grid, originX, originY, originZ)) {
            val previous = grid[write.x, write.y, write.z]
            if (write.value != previous) {
                grid[write.x, write.y, write.z] = write.value
                changes += CellChange(
                    index = write.index,
                    x = write.x,
                    y = write.y,
                    z = write.z,
                    before = previous,
                    after = write.value,
                )
            }
        }
        return StepDelta(changes)
    }

    internal fun writes(
        grid: VoxelGrid,
        originX: Int,
        originY: Int,
        originZ: Int,
    ): List<RuleWrite> = buildList {
        for (z in 0 until sizeZ) {
            for (y in 0 until sizeY) {
                for (x in 0 until sizeX) {
                    val patternIndex = x + y * sizeX + z * sizeX * sizeY
                    val value = output[patternIndex]
                    if (value == UNCHANGED) continue
                    val worldX = originX + x
                    val worldY = originY + y
                    val worldZ = originZ + z
                    add(RuleWrite(
                        index = grid.index(worldX, worldY, worldZ),
                        x = worldX,
                        y = worldY,
                        z = worldZ,
                        value = value,
                    ))
                }
            }
        }
    }

    internal fun inputMaskAt(index: Int): Int = input[index]

    internal fun outputValueAt(index: Int): Byte = output[index]

    internal fun exactInputValueAt(index: Int): Byte? {
        val mask = input[index]
        return if (Integer.bitCount(mask) == 1) Integer.numberOfTrailingZeros(mask).toByte() else null
    }

    internal fun transformed(permutation: IntArray, signs: IntArray): Rule {
        require(permutation.size == 3 && permutation.toSet() == setOf(0, 1, 2))
        require(signs.size == 3 && signs.all { it == -1 || it == 1 })

        val sourceSizes = intArrayOf(sizeX, sizeY, sizeZ)
        val targetSizes = IntArray(3)
        for (sourceAxis in 0..2) {
            targetSizes[permutation[sourceAxis]] = sourceSizes[sourceAxis]
        }

        val targetInput = IntArray(input.size)
        val targetOutput = ByteArray(output.size)
        for (z in 0 until sizeZ) {
            for (y in 0 until sizeY) {
                for (x in 0 until sizeX) {
                    val source = intArrayOf(x, y, z)
                    val target = IntArray(3)
                    for (sourceAxis in 0..2) {
                        val coordinate = source[sourceAxis]
                        target[permutation[sourceAxis]] = if (signs[sourceAxis] > 0) {
                            coordinate
                        } else {
                            sourceSizes[sourceAxis] - 1 - coordinate
                        }
                    }

                    val sourceIndex = x + y * sizeX + z * sizeX * sizeY
                    val targetIndex =
                        target[0] + target[1] * targetSizes[0] +
                            target[2] * targetSizes[0] * targetSizes[1]
                    targetInput[targetIndex] = input[sourceIndex]
                    targetOutput[targetIndex] = output[sourceIndex]
                }
            }
        }

        return Rule(
            input = targetInput,
            output = targetOutput,
            sizeX = targetSizes[0],
            sizeY = targetSizes[1],
            sizeZ = targetSizes[2],
            probability = probability,
        )
    }

    internal fun structuralKey(): RuleKey = RuleKey(
        input = input.toList(),
        output = output.toList(),
        sizeX = sizeX,
        sizeY = sizeY,
        sizeZ = sizeZ,
    )

    companion object {
        const val UNCHANGED: Byte = -1

        internal fun masked(
            input: IntArray,
            output: ByteArray,
            sizeX: Int,
            sizeY: Int,
            sizeZ: Int,
            probability: Double = 1.0,
        ): Rule = Rule(
            input = input.copyOf(),
            output = output.copyOf(),
            sizeX = sizeX,
            sizeY = sizeY,
            sizeZ = sizeZ,
            probability = probability,
        )

        fun single(from: Byte, to: Byte): Rule = Rule(
            input = byteArrayOf(from),
            output = byteArrayOf(to),
            sizeX = 1,
            sizeY = 1,
            sizeZ = 1,
        )
    }
}

internal data class RuleKey(
    val input: List<Int>,
    val output: List<Byte>,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
)

internal data class RuleWrite(
    val index: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val value: Byte,
)

private fun exactMasks(values: ByteArray): IntArray = IntArray(values.size) { index ->
    val value = values[index].toInt()
    require(value in 0..30) { "Rule values must be in 0..30" }
    1 shl value
}
