import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.util.Properties

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("app.cash.sqldelight")
    id("com.android.kotlin.multiplatform.library")
}

val pocketLabRelease = Properties().apply {
    rootProject.file("release.properties").inputStream().use(::load)
}
val pocketLabVersion = pocketLabRelease.getProperty("versionName")
    ?: error("release.properties is missing versionName")
val pocketLabBuild = pocketLabRelease.getProperty("buildNumber")
    ?.takeIf { value -> value.toIntOrNull() != null }
    ?: error("release.properties has an invalid buildNumber")
version = pocketLabVersion

kotlin {
    // A plain JVM target gives us a fast feedback loop and makes the distinction
    // between "target" and "Android" visible in the Gradle model.
    jvm()

    // The Android-KMP plugin creates an Android target without Android build
    // variants. Host tests are opt-in and run on the local JVM.
    android {
        namespace = "dev.kmpjourney.shared"
        compileSdk = 35
        minSdk = 24

        withHostTestBuilder {}.configure {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // A framework belongs to one platform/architecture variant. XCFramework
    // gathers the device and Simulator variants into one distributable bundle.
    val appleBundle = XCFramework("PocketLabShared")
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { appleTarget ->
        appleTarget.binaries.framework {
            baseName = "PocketLabShared"
            isStatic = true
            binaryOption("bundleId", "dev.kmpjourney.pocketlab.shared")
            binaryOption("bundleShortVersionString", pocketLabVersion)
            binaryOption("bundleVersion", pocketLabBuild)
            appleBundle.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Kotlin consumers see domain transitively. Apple consumers do not:
            // the framework deliberately links domain without exporting its API.
            api(project(":domain"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("io.ktor:ktor-client-core:3.6.1")
            implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("app.cash.sqldelight:runtime:2.4.1")
            implementation("app.cash.sqldelight:coroutines-extensions:2.4.1")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("io.ktor:ktor-client-mock:3.6.1")
        }

        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.6.1")
            implementation("app.cash.sqldelight:sqlite-driver:2.4.1")
        }

        androidMain.dependencies {
            implementation("io.ktor:ktor-client-android:3.6.1")
            implementation("app.cash.sqldelight:android-driver:2.4.1")
        }

        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.6.1")
            implementation("app.cash.sqldelight:native-driver:2.4.1")
        }

        named("androidHostTest") {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.4.1")
            }
        }
    }
}

sqldelight {
    databases {
        create("PocketLabSqlDatabase") {
            packageName.set("dev.kmpjourney.pocketlab.data")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
