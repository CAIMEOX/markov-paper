plugins {
    kotlin("jvm")
    `java-library`
}

repositories { mavenCentral() }
dependencies {
    api(project(":core"))
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
