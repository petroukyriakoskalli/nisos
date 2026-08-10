// The Android app. Kept in the same repository as the Python program on
// purpose: the Python is the reference implementation and the place the design
// arguments are written down, and every table in `core/` below is a port of a
// file over there that already has tests proving what it should do.

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
    }
}

rootProject.name = "nisos"
include(":app")
