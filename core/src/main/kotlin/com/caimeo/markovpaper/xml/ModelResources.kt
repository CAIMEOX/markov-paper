package com.caimeo.markovpaper.xml

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader

/** Relative resource paths are shared by the plugin data folder and bundled MJ resources. */
class ModelResources(dataFolder: Path? = null) {
    private val directory = dataFolder?.toAbsolutePath()?.normalize()

    fun open(relativePath: String): InputStream {
        require(relativePath.split('/').all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "." && it != ".." }) {
            "Invalid model resource path '$relativePath'"
        }
        if (directory != null) {
            val file = directory.resolve(relativePath)
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                require(file.toRealPath().startsWith(directory.toRealPath())) {
                    "Model resource escapes data folder: '$relativePath'"
                }
                require(Files.isRegularFile(file)) { "Model resource is not a file: '$relativePath'" }
                return Files.newInputStream(file)
            }
        }
        return requireNotNull(javaClass.getResourceAsStream("/markovjunior/$relativePath")) {
            "Missing model resource '$relativePath' (data folder and bundled resources)"
        }
    }
}

internal object XmlDocuments {
    private fun factory() = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }

    fun parse(xml: String): Element = factory().newDocumentBuilder()
        .parse(InputSource(StringReader(xml.trimStart('\uFEFF')))).documentElement

    fun parse(stream: InputStream): Element = stream.use {
        factory().newDocumentBuilder().parse(it).documentElement
    }
}
