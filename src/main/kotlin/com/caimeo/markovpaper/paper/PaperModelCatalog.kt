package com.caimeo.markovpaper.paper

import com.caimeo.markovpaper.xml.ModelResources
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** One path from installed XML + profile to validated bounds, palette and runtime factory. */
internal class PaperModelCatalog(modelDirectory: Path) {
    private val directory = modelDirectory.toAbsolutePath().normalize()
    private val resources = ModelResources(directory.parent)
    init { Files.createDirectories(directory) }

    fun names(): List<String> = Files.list(directory).use { files ->
        files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
            .map { it.fileName.toString().removeSuffix(".xml") }.filter { it.matches(NAME) }.sorted().toList()
    }

    fun contains(name: String): Boolean = name.matches(NAME) && Files.isRegularFile(path(name, "xml"))

    fun profile(name: String): ModelProfile = contextual(name) {
        val file = path(name, "properties")
        ModelProfile.parse(if (Files.exists(file, NOFOLLOW_LINKS)) read(file) else "")
    }

    fun prepare(name: String, size: Int, height: Int? = null, maxCells: Long = MAX_GENERATION_VOXELS): PreparedPaperModel {
        val profile = profile(name)
        return contextual(name) { profile.prepare(read(path(name, "xml")), size, height, resources, maxCells) }
    }

    /** Installs new files only. Existing user XML and profiles are never overwritten. */
    fun installBundled() {
        for (name in bundledNames) {
            install("/models/$name.xml", path(name, "xml"))
            install("/model-profiles/$name.properties", path(name, "properties"))
        }
    }

    private fun install(resource: String, target: Path) {
        if (Files.exists(target, NOFOLLOW_LINKS)) return
        requireNotNull(javaClass.getResourceAsStream(resource)) { "Missing bundled resource $resource" }.use {
            Files.copy(it, target)
        }
    }

    private fun read(file: Path): String {
        require(Files.isRegularFile(file)) { "Missing model file $file" }
        require(file.toRealPath().startsWith(directory.toRealPath())) { "Model file escapes model directory: $file" }
        return Files.readString(file)
    }

    private fun path(name: String, extension: String): Path {
        require(name.matches(NAME)) { "Invalid model name '$name'" }
        return directory.resolve("$name.$extension")
    }

    private fun <T> contextual(name: String, operation: () -> T): T = try { operation() } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("Model '$name': ${exception.message}", exception)
    }

    companion object {
        private val NAME = Regex("[a-z0-9_-]+")
        val bundledNames: List<String> = requireNotNull(PaperModelCatalog::class.java.getResource("/model-profiles/index.txt"))
            .readText().lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList().also { names ->
                require(names.distinct().size == names.size && names.all { it.matches(NAME) })
            }
    }
}
