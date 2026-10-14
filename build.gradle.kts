plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}

/** Product version from the VERSION file (CLAUDE.md §9). */
val productVersion: String = rootProject.file("VERSION").readText().trim()

allprojects {
    version = productVersion
}

// --- Release guards (CLAUDE.md §9) -----------------------------------------------------------

val verifyVersionMatchesTag = tasks.register("verifyVersionMatchesTag") {
    group = "verification"
    description = "Fails unless VERSION equals the newest vX.Y.Z git tag."
    val versionFile = rootProject.file("VERSION")
    val tagList = providers.exec {
        commandLine("git", "tag", "--list", "v*", "--sort=-v:refname")
    }.standardOutput.asText
    inputs.file(versionFile)
    outputs.upToDateWhen { false }
    doLast {
        val newestTag = tagList.get().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: throw GradleException("No vX.Y.Z tag found. Tag the commit that sets VERSION (CLAUDE.md §9).")
        val expected = "v" + versionFile.readText().trim()
        if (newestTag != expected) {
            throw GradleException(
                "VERSION is ${expected.removePrefix("v")} but the newest tag is $newestTag. " +
                    "Bump VERSION and tag the commit; never weaken this check (CLAUDE.md §9)."
            )
        }
    }
}

val verifyReleaseNotes = tasks.register("verifyReleaseNotes") {
    group = "verification"
    description = "Fails unless the newest section of docs/RELEASE_NOTES.md is for VERSION."
    val versionFile = rootProject.file("VERSION")
    val notesFile = rootProject.file("docs/RELEASE_NOTES.md")
    inputs.files(versionFile, notesFile)
    doLast {
        val heading = Regex("""^## v(\d+\.\d+\.\d+) — \d{4}-\d{2}-\d{2}\s*$""")
        val newest = notesFile.readLines().firstNotNullOfOrNull { heading.find(it)?.groupValues?.get(1) }
            ?: throw GradleException("docs/RELEASE_NOTES.md has no '## vX.Y.Z — YYYY-MM-DD' section.")
        val version = versionFile.readText().trim()
        if (newest != version) {
            throw GradleException("Newest release notes section is v$newest but VERSION is $version (CLAUDE.md §9).")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyVersionMatchesTag, verifyReleaseNotes)
}
