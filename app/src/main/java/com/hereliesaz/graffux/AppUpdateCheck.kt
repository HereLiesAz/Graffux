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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where an update for this install comes from. It follows the install source and never crosses
 * channels: a Play install is signed by Play App Signing and a GitHub APK by the upload key, so
 * Android refuses to install one over the other.
 */
enum class UpdateChannel(val label: String) { PLAY("Google Play"), GITHUB("GitHub") }

sealed interface UpdateStatus {
    data class UpToDate(val channel: UpdateChannel) : UpdateStatus
    data class Available(val channel: UpdateChannel, val version: String, val uri: Uri) : UpdateStatus
    data class Failed(val channel: UpdateChannel, val reason: String) : UpdateStatus
}

/** Checks the install's own channel for a newer Graffux and opens it there. */
object AppUpdateCheck {
    private const val PLAY_STORE = "com.android.vending"
    private const val LATEST_RELEASE = "https://api.github.com/repos/HereLiesAz/Graffux/releases/latest"
    private const val TIMEOUT_MS = 10_000

    fun channel(context: Context): UpdateChannel =
        if (installerOf(context) == PLAY_STORE) UpdateChannel.PLAY else UpdateChannel.GITHUB

    suspend fun check(context: Context): UpdateStatus {
        val channel = channel(context)
        return runCatching {
            when (channel) {
                UpdateChannel.PLAY -> checkPlay(context)
                UpdateChannel.GITHUB -> checkGitHub(BuildConfig.VERSION_NAME)
            }
        }.getOrElse { UpdateStatus.Failed(channel, it.message ?: it.javaClass.simpleName) }
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
        val connection = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub answered ${connection.responseCode}" }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = release.getString("tag_name")
            if (isNewer(tag, installed)) {
                val page = Uri.parse(release.getString("html_url"))
                UpdateStatus.Available(UpdateChannel.GITHUB, tag.removePrefix("v"), page)
            } else {
                UpdateStatus.UpToDate(UpdateChannel.GITHUB)
            }
        } finally {
            connection.disconnect()
        }
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
