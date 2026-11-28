import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 ships built-in Kotlin support, so org.jetbrains.kotlin.android is intentionally not applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.kaff.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.kaff.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources.localeFilters += listOf("ar", "en")
    }

    // The upload key lives outside the repo (~/.kaff-signing, or KAFF_SIGNING_PROPERTIES). Without it,
    // release builds fall back to the debug key so CI and contributors can still build them.
    val signingFile = (System.getenv("KAFF_SIGNING_PROPERTIES") ?: "${System.getProperty("user.home")}/.kaff-signing/keystore.properties")
        .let(::file)
    val upload = signingFile.takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (upload != null) {
            create("upload") {
                storeFile = file(upload.getProperty("storeFile"))
                storePassword = upload.getProperty("storePassword")
                keyAlias = upload.getProperty("keyAlias")
                keyPassword = upload.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The MediaPipe model is memory-mapped by the task runner and must stay uncompressed.
        noCompress += "task"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Store screenshots render the real composables under Robolectric (see StoreScreenshots).
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("roborazzi.test.record", "true")
            providers.gradleProperty("screenshots").orNull?.let { test.systemProperty("kaff.screenshots", it) }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// The hand landmarker model is fetched by scripts/fetch_model.sh and is not committed.
val verifyHandModel by tasks.registering {
    val model = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")
    inputs.files(model).optional()
    doLast {
        check(model.asFile.length() > 0) {
            "Missing ${model.asFile}. Run scripts/fetch_model.sh first."
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyHandModel) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mediapipe.tasks.vision)

    implementation(libs.play.services.nearby)
    // QR codes for the desktop address and the Kaff hotspot (encoder only, no camera scanning).
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
