package com.hereliesaz.graffux

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where an update for this install comes from. It follows the install source and never crosses
 * channels: a Play install is signed by Play App Signing and a GitHub APK by the upload key, so
 * Android refuses to install one over the other.
 */
enum class UpdateChannel(val label: String) {
    PLAY("Google Play"),
    GITHUB("GitHub"),

    /** Another store (Galaxy Store, Amazon, F-Droid...): it signs and updates its own builds. */
    OTHER("your app store"),
}

sealed interface UpdateStatus {
    data class UpToDate(val channel: UpdateChannel) : UpdateStatus
    data class Available(val channel: UpdateChannel, val version: String, val uri: Uri) : UpdateStatus
    data class Failed(val channel: UpdateChannel, val reason: String) : UpdateStatus
}

/** Checks the install's own channel for a newer Graffux and opens it there. */
object AppUpdateCheck {
    private const val PLAY_STORE = "com.android.vending"

    /** Installers that mean "the user installed an APK by hand" — the GitHub channel's build. */
    private val SIDELOAD_INSTALLERS = setOf(
        null,
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.shell",
    )
    /** Recent releases, not `/latest`: a build tag (1.50.27.2) can follow its group tag (1.50.27). */
    private const val RELEASES = "https://api.github.com/repos/HereLiesAz/Graffux/releases?per_page=30"
    private const val TIMEOUT_MS = 10_000

    fun channel(context: Context): UpdateChannel = when (val installer = installerOf(context)) {
        PLAY_STORE -> UpdateChannel.PLAY
        in SIDELOAD_INSTALLERS -> UpdateChannel.GITHUB
        else -> if (installer.isNullOrBlank()) UpdateChannel.GITHUB else UpdateChannel.OTHER
    }

    // Play's task, the network and the JSON each throw their own types; all mean "couldn't check".
    @Suppress("TooGenericExceptionCaught")
    suspend fun check(context: Context): UpdateStatus {
        val channel = channel(context)
        return try {
            when (channel) {
                UpdateChannel.PLAY -> checkPlay(context)
                UpdateChannel.GITHUB -> checkGitHub(BuildConfig.VERSION_NAME)
                // Never offer a GitHub APK to another store's install: its signature differs.
                UpdateChannel.OTHER -> UpdateStatus.UpToDate(channel)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateStatus.Failed(channel, plainReason(e))
        }
    }

    private fun plainReason(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "no internet connection"
        is java.net.SocketTimeoutException -> "the connection timed out"
        else -> "the update service didn't answer"
    }

    /** Takes the user to the update: the Play listing (app, else web) or the GitHub release page. */
    fun open(context: Context, update: UpdateStatus.Available) {
        val view = { uri: Uri ->
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        try {
            view(update.uri)
        } catch (_: ActivityNotFoundException) {
            if (update.channel == UpdateChannel.PLAY) {
                view(Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
            }
        }
    }

    private suspend fun checkPlay(context: Context): UpdateStatus {
        val info = AppUpdateManagerFactory.create(context).appUpdateInfo.await()
        return if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
            UpdateStatus.Available(
                UpdateChannel.PLAY,
                "build ${info.availableVersionCode()}",
                Uri.parse("market://details?id=${context.packageName}"),
            )
        } else {
            UpdateStatus.UpToDate(UpdateChannel.PLAY)
        }
    }

    private suspend fun checkGitHub(installed: String): UpdateStatus = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub answered ${connection.responseCode}" }
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val newest = (0 until releases.length())
                .map { releases.getJSONObject(it) }
                .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                .maxWithOrNull(compareBy(versionOrder) { it.getString("tag_name") })
            if (newest != null && isNewer(newest.getString("tag_name"), installed)) {
                val page = Uri.parse(newest.getString("html_url"))
                UpdateStatus.Available(UpdateChannel.GITHUB, newest.getString("tag_name").removePrefix("v"), page)
            } else {
                UpdateStatus.UpToDate(UpdateChannel.GITHUB)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Orders tags numerically, field by field; a missing field counts as 0. */
    internal val versionOrder: Comparator<String> = Comparator { a, b ->
        val x = numbers(a.removePrefix("v"))
        val y = numbers(b.removePrefix("v"))
        (0 until maxOf(x.size, y.size))
            .map { x.getOrElse(it) { 0 }.compareTo(y.getOrElse(it) { 0 }) }
            .firstOrNull { it != 0 } ?: 0
    }

    /**
     * True when release [tag] (`1.50.26`) is newer than [installed] (`1.50.26.1`). Only the fields the
     * tag names are compared: the installed name's extra build field is the same release.
     */
    internal fun isNewer(tag: String, installed: String): Boolean {
        val release = numbers(tag.removePrefix("v"))
        val current = numbers(installed)
        val differing = release.indices.firstOrNull { release[it] != current.getOrElse(it) { 0 } }
        return differing != null && release[differing] > current.getOrElse(differing) { 0 }
    }

    private fun numbers(version: String): List<Int> =
        version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    private fun installerOf(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
}
