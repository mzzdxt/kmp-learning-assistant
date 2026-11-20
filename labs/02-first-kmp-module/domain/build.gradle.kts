import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

version = "0.17.0"

kotlin {
    jvm()

    android {
        namespace = "dev.kmpjourney.domain"
        compileSdk = 35
        minSdk = 24

        withHostTestBuilder {}.configure {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // StateFlow appears in DraftEditorStore's public API, so this is an
            // API dependency of the domain module rather than an implementation detail.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
