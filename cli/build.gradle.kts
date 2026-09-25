plugins {
    kotlin("jvm")
    application
}

repositories { mavenCentral() }
dependencies {
    implementation(project(":core"))
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
application {
    mainClass.set("com.caimeo.markovpaper.cli.MainKt")
    applicationName = "markov"
}
distributions {
    main {
        contents {
            from(rootProject.file("docs")) {
                include("cli.md", "model-format.md", "core.md", "paper.md", "fabric.md", "model-profiles.md", "concepts.md")
                into("docs")
            }
            from(rootProject.file("THIRD_PARTY_NOTICES.md"))
        }
    }
}
tasks.test { useJUnitPlatform() }
tasks.jar {
    archiveBaseName.set("markov-cli")
    archiveClassifier.set("thin")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
}
val standaloneJar = tasks.register<Jar>("standaloneJar") {
    dependsOn(":core:jar", tasks.classes)
    archiveBaseName.set("markov-cli")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.assemble { dependsOn(standaloneJar) }
