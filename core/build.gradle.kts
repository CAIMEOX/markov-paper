import java.security.MessageDigest
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

plugins {
    kotlin("jvm")
    `java-library`
}

repositories { mavenCentral() }
dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.test { useJUnitPlatform() }

abstract class VerifyBundledResources : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val checksums: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val entries = checksums.get().asFile.readLines().filter { it.isNotBlank() && !it.startsWith('#') }
        require(entries.isNotEmpty()) { "Bundled resource manifest is empty" }
        for (entry in entries) {
            val parts = entry.split(Regex("\\s+"), limit = 2)
            require(parts.size == 2 && parts[0].matches(Regex("[a-f0-9]{64}"))) { "Invalid checksum entry: $entry" }
            val path = parts[1]
            require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid resource path: $path" }
            val file = resourcesDirectory.file(path).get().asFile
            require(file.isFile) { "Missing bundled resource: $path" }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            require(digest == parts[0]) { "Bundled resource checksum mismatch: $path" }
        }
        logger.lifecycle("Verified {} vendored MarkovJunior resources", entries.size)
    }
}

val verifyBundledResources = tasks.register<VerifyBundledResources>("verifyBundledResources") {
    checksums.set(layout.projectDirectory.file("vendor/markovjunior.sha256"))
    resourcesDirectory.set(layout.projectDirectory.dir("src/main/resources"))
}

tasks.processResources {
    dependsOn(verifyBundledResources)
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
}
