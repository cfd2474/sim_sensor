package com.taksolutions.simsensor.server

/** Facts baked into the build. */
object BuildInfo {
    /** Product version from the root VERSION file, or "dev" when run without the resource. */
    val version: String by lazy {
        BuildInfo::class.java.getResource("/simsensor-version.txt")?.readText()?.trim() ?: "dev"
    }
}
