pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // The Kotlin/JS plugin adds Node.js and Yarn download repositories at project level.
    // PREFER_SETTINGS ignores those, so they are declared here instead.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        ivy("https://nodejs.org/dist/") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "sim_sensor"

include(":shared")
include(":server")

// The Android app needs an Android SDK. Server-only builds (the server Docker image)
// set SIMSENSOR_SKIP_ANDROID=1 so they don't need one.
if (System.getenv("SIMSENSOR_SKIP_ANDROID") == null) {
    include(":android")
}
