import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The Rust engine is cross-compiled by scripts/build_rust.py. CMake is only
// wired in once its output exists, so a checkout without the Rust toolchain
// still configures, tests and assembles cleanly.
val rustEngineLibrary = layout.projectDirectory
    .file("src/main/jniLibs/arm64-v8a/liboffset_core.so")
    .asFile
val rustEnginePresent = rustEngineLibrary.exists()

android {
    namespace = "com.chimerateam.offsetextractor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chimerateam.offsetextractor"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (rustEnginePresent) {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        disable += setOf(
            // Version-nag checks: these report newer upstream releases, not defects in this code.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
            "NewerVersionAvailable",
            // The engine ships arm64-v8a only by design: the target device is a
            // 64-bit ARM tablet and there is no x86 native library to offer.
            "ChromeOsAbiSupport",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    if (rustEnginePresent) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1+"
            }
        }
    }
}

/**
 * Cross-compiles the Rust engine and stages liboffset_core.so in jniLibs.
 *
 * The script exits successfully when the Rust toolchain is absent, so this
 * never blocks a build on a machine that only needs the Kotlin code.
 */
val buildRustEngine by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compile the Rust engine for arm64-v8a."
    workingDir = rootProject.projectDir
    commandLine("python3", "scripts/build_rust.py")
    isIgnoreExitValue = false
    outputs.upToDateWhen { false }
}

tasks.named("preBuild") {
    dependsOn(buildRustEngine)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.gson)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
