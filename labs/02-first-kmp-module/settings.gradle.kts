pluginManagement {
    // AGP 8.13.2 bundles an older R8 that warns on Kotlin 2.4 metadata.
    // Android's compatibility table requires R8 9.1.29+ for Kotlin 2.4.
    buildscript {
        repositories {
            google()
            mavenCentral()
            maven("https://storage.googleapis.com/r8-releases/raw")
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "first-kmp-module"
include(":domain")
include(":shared")
include(":androidApp")

