plugins {
    kotlin("jvm") version "2.3.20"
}

evaluationDependsOn(":core")
evaluationDependsOn(":minecraft")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":minecraft"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.119-stable")
    implementation(kotlin("stdlib"))

    testImplementation("io.papermc.paper:paper-api:26.2.build.119-stable")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
    from("THIRD_PARTY_NOTICES.md")
}

tasks.jar {
    dependsOn(":core:jar", ":minecraft:jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
