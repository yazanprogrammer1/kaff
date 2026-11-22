// Kaff for Windows: a separate build so Compose Desktop's Kotlin/JVM plugins never meet AGP's.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Downloads a full JDK (with jpackage) for packaging, whatever JDK runs Gradle.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "kaff-desktop"
