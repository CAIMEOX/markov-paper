plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom") version "1.17.21"
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.3")
    implementation("net.fabricmc:fabric-language-kotlin:1.13.10+kotlin.2.3.20")
    implementation(project(":minecraft"))
    include(project(":minecraft"))
    include(project(":core"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }
base { archivesName.set("markov-fabric") }

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("modVersion", modVersion)
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
}
