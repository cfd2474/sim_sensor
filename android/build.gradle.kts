plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * versionCode is derived from VERSION so it always rises with every release
 * (CLAUDE.md §9): MAJOR*10000 + MINOR*100 + PATCH. Minor and patch must stay below 100.
 */
val productVersion: String = rootProject.file("VERSION").readText().trim()
val productVersionCode: Int = productVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "VERSION minor/patch must be < 100 to derive versionCode" }
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "com.taksolutions.simsensor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.taksolutions.simsensor"
        minSdk = 26
        targetSdk = 36
        versionCode = productVersionCode
        versionName = productVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release signing is configured in a later chunk (key kept outside the repo).
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
