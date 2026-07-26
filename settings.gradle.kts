@file:Suppress("UnstableApiUsage")

pluginManagement {
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
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.philburk")
            }
        }
    }
}

rootProject.name = "iGpod"

include(":misc:audiofxstub")
include(":misc:audiofxstub2")
include(":misc:audiofxfwd")
include(":app")
include(":baselineprofile")
