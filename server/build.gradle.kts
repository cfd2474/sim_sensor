plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.taksolutions.simsensor.server.ApplicationKt")
    applicationName = "simsensor-server"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.logback.classic)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.bouncycastle)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers)
}

tasks.test {
    // Integration tests start a TimescaleDB container; Docker must be running.
    systemProperty("simsensor.test.dbImage", "timescale/timescaledb:2.22.1-pg16")
}

// Bake the product version (root VERSION file) into the jar as a resource.
val generateVersionResource = tasks.register("generateVersionResource") {
    val versionFile = rootProject.file("VERSION")
    val outDir = layout.buildDirectory.dir("generated/version-resource")
    inputs.file(versionFile)
    outputs.dir(outDir)
    doLast {
        outDir.get().file("simsensor-version.txt").asFile.apply {
            parentFile.mkdirs()
            writeText(versionFile.readText().trim())
        }
    }
}
sourceSets.main {
    resources.srcDir(generateVersionResource)
}
