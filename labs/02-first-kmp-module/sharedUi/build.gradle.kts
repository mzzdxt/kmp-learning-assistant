import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

version = "0.1.0-spike"

kotlin {
    androidLibrary {
        namespace = "dev.kmpjourney.pocketlab.sharedui"
        compileSdk = 36
        minSdk = 24
        withHostTestBuilder {}.configure {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()

    listOf(iosArm64, iosSimulatorArm64).forEach { target ->
        target.binaries.framework {
            baseName = "PocketLabSharedUi"
            isStatic = true
            binaryOption("bundleId", "dev.kmpjourney.pocketlab.sharedui")
            binaryOption("bundleShortVersionString", "0.1.0")
            binaryOption("bundleVersion", "1")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
            implementation("org.jetbrains.compose.ui:ui:1.11.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
