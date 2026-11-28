import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "dev.kaff"
version = "1.0.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    testImplementation(kotlin("test"))
}

// Packaging needs jpackage, which Android Studio's JBR lacks: use a full Temurin JDK, downloaded by
// the foojay resolver when it is not installed.
val packagingJdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

compose.desktop {
    application {
        mainClass = "dev.kaff.desktop.MainKt"
        javaHome = packagingJdk.get().metadata.installationPath.asFile.absolutePath
        // Release packages run ProGuard: unused Compose, Material and Kotlin code is dropped.
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(true)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Kaff"
            packageVersion = "1.0.1"
            description = "Kaff for Windows: move files with your phone, no internet needed"
            vendor = "Kaff"
            copyright = "© 2026 Kaff"
            modules("java.net.http", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("icons/kaff.ico"))
                menuGroup = "Kaff"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed so updates replace the installed version instead of installing a second copy.
                upgradeUuid = "6f1d2c8e-4b7a-4e5c-9a3d-2b8f0e7c5a19"
            }
        }
    }
}
