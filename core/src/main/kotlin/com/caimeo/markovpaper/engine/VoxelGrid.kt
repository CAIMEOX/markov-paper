package com.caimeo.markovpaper.engine

/** A live read-only grid view. copyState() gives an owned x-fastest snapshot. */
interface GridView {
    val sizeX: Int
    val sizeY: Int
    val sizeZ: Int
    operator fun get(x: Int, y: Int, z: Int): Byte
    fun copyState(): ByteArray
    fun index(x: Int, y: Int, z: Int): Int = x + y * sizeX + z * sizeX * sizeY
}

class VoxelGrid(
    override val sizeX: Int,
    override val sizeY: Int,
    override val sizeZ: Int,
) : GridView {
    private val state = ByteArray(sizeX * sizeY * sizeZ)

    init {
        require(sizeX > 0 && sizeY > 0 && sizeZ > 0)
    }

    override operator fun get(x: Int, y: Int, z: Int): Byte = state[index(x, y, z)]

    operator fun set(x: Int, y: Int, z: Int, value: Byte) {
        state[index(x, y, z)] = value
    }

    override fun copyState(): ByteArray = state.copyOf()

    internal fun readOnly(): GridView = object : GridView {
        override val sizeX get() = this@VoxelGrid.sizeX
        override val sizeY get() = this@VoxelGrid.sizeY
        override val sizeZ get() = this@VoxelGrid.sizeZ
        override fun get(x: Int, y: Int, z: Int) = this@VoxelGrid[x, y, z]
        override fun copyState() = this@VoxelGrid.copyState()
    }
}

data class CellChange(
    val index: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val before: Byte,
    val after: Byte,
)

data class StepDelta(val changes: List<CellChange>)
