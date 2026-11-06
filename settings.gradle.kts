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

rootProject.name = "Castify"

// Clean architecture boundaries are enforced by these modules (see docs/ARCHITECTURE.md).
include(":core:domain")
include(":core:protocol")
include(":phone:data")
include(":phone:app")
include(":tv:data")
include(":tv:app")
