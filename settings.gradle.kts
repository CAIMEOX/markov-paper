pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "markov-paper"
include("core", "cli", "minecraft", "fabric")
