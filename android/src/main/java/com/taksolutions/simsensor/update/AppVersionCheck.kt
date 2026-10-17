package com.taksolutions.simsensor.update

/** The app the connected server offers on its download link (`GET /api/app`). */
data class OfferedApp(val version: String, val versionCode: Int, val downloadUrl: String)

/** Who installed this copy, which decides how it may be updated (platform reference §7). */
enum class InstallSource {
    /**
     * Google Play. Not how agencies install it (operator, 2026-10-17: Play only signs the app), but
     * if a copy ever comes from Play, Play's policy allows updates only through Play.
     */
    PLAY,

    /** The phone's package installer or adb: a sideloaded APK, updated from the download link. */
    SIDELOAD,

    /** Anything else, normally the agency's MDM: it installs updates itself. */
    MANAGED,
    ;

    companion object {
        const val PLAY_PACKAGE = "com.android.vending"
        private val SIDELOAD_INSTALLERS = setOf(
            null,
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.shell",
        )

        /** From the installing package name ([android.content.pm.InstallSourceInfo]). */
        fun of(installer: String?): InstallSource = when (installer) {
            PLAY_PACKAGE -> PLAY
            in SIDELOAD_INSTALLERS -> SIDELOAD
            else -> MANAGED
        }
    }
}

/** What the prompt's button does. */
sealed interface UpdateAction {
    /** The app's Google Play listing, opened in the Play Store app. */
    data class OpenPlay(val listingUrl: String) : UpdateAction

    /** The server's download link, opened in the browser. */
    data class OpenDownload(val url: String) : UpdateAction

    /** No button: the MDM (or kiosk mode) is in charge of installing apps. */
    data object AskAdministrator : UpdateAction
}

/** An update to offer: this phone has [installedVersion], the server offers [offered]. */
data class AppUpdate(val installedVersion: String, val offered: OfferedApp, val action: UpdateAction)

/**
 * Decides whether this phone's app is behind the one its server offers, and how to update it
 * (operator, 2026-10-17). Compared by versionCode: server-only releases have no app of their own,
 * so the server's own version is not the measure. A phone newer than the server's app is fine.
 */
object AppVersionCheck {
    fun playListing(packageName: String) = "https://play.google.com/store/apps/details?id=$packageName"

    fun update(
        installedVersionCode: Int,
        installedVersion: String,
        offered: OfferedApp?,
        source: InstallSource,
        kioskMode: Boolean,
        packageName: String,
    ): AppUpdate? {
        if (offered == null || offered.versionCode <= installedVersionCode) return null
        val action = when {
            // Kiosk mode can't open other apps (platform reference §7).
            kioskMode -> UpdateAction.AskAdministrator
            source == InstallSource.PLAY -> UpdateAction.OpenPlay(playListing(packageName))
            source == InstallSource.SIDELOAD -> UpdateAction.OpenDownload(offered.downloadUrl)
            else -> UpdateAction.AskAdministrator
        }
        return AppUpdate(installedVersion, offered, action)
    }
}
