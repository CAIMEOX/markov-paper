package com.caimeo.markovpaper.paper

data class DebugBuildStamp(
    val schema: Int,
    val revision: String,
    val expected: Int,
    val placed: Int,
    val failed: Int,
) {
    fun isCurrent(
        currentSchema: Int,
        currentRevision: String,
        currentExpected: Int,
    ): Boolean =
        schema == currentSchema &&
            revision == currentRevision &&
            expected == currentExpected

    fun isCurrentComplete(
        currentSchema: Int,
        currentRevision: String,
        currentExpected: Int,
    ): Boolean =
        isCurrent(currentSchema, currentRevision, currentExpected) &&
            placed == expected &&
            failed == 0

    fun allowsPlacementOf(loadedAssets: Int): Boolean =
        failed == 0 && loadedAssets == expected

    fun canRetryInPlace(): Boolean = placed == 0

    fun encode(): String = listOf(schema, revision, expected, placed, failed).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"

        fun parse(encoded: String?): DebugBuildStamp? {
            val parts = encoded?.split(SEPARATOR, limit = 5) ?: return null
            if (parts.size != 5) return null
            return DebugBuildStamp(
                schema = parts[0].toIntOrNull() ?: return null,
                revision = parts[1].takeIf(String::isNotBlank) ?: return null,
                expected = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null,
                placed = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null,
                failed = parts[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null,
            )
        }
    }
}
