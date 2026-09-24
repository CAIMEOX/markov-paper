package com.caimeo.markovpaper.paper

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object DebugWorldOwnership {
    const val MARKER_FILE = ".markov-paper-asset-debug"

    fun requireOwnedOrAbsent(worldDirectory: Path) {
        if (Files.exists(worldDirectory) && !Files.isRegularFile(worldDirectory.resolve(MARKER_FILE))) {
            throw IllegalArgumentException(
                "World directory '${worldDirectory.fileName}' exists without Markov Paper ownership marker"
            )
        }
    }

    fun markOwned(worldDirectory: Path) {
        Files.createDirectories(worldDirectory)
        Files.writeString(
            worldDirectory.resolve(MARKER_FILE),
            "markov-paper asset debug world\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    fun dimensionDirectory(primaryWorldDirectory: Path, worldName: String): Path {
        val namespaceDirectory = primaryWorldDirectory.parent
        val dimensionsDirectory = namespaceDirectory?.parent
        val storageRoot = if (
            primaryWorldDirectory.fileName?.toString() == "overworld" &&
            namespaceDirectory?.fileName?.toString() == "minecraft" &&
            dimensionsDirectory?.fileName?.toString() == "dimensions"
        ) {
            requireNotNull(dimensionsDirectory.parent)
        } else {
            primaryWorldDirectory
        }
        return storageRoot.resolve("dimensions").resolve("minecraft").resolve(worldName)
    }
}
