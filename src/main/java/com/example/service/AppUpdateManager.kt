package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class UpdateDownloadState {
    object Idle : UpdateDownloadState()
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : UpdateDownloadState()
    data class ReadyToInstall(val apkFile: File) : UpdateDownloadState()
    data class Error(val message: String) : UpdateDownloadState()
}

class AppUpdateManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val tag = "AppUpdateManager"

    private fun packageInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    fun getCurrentVersionName(): String {
        return try {
            packageInfo().versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            PackageInfoCompat.getLongVersionCode(packageInfo())
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Reads `app_version.json` from the source announced by the GitLab pointer
     * ([UpdateSourceResolver]). No GitHub API call is ever made. Every failure (no source, 404,
     * bad JSON, offline) is returned as `Result.failure` and is silent for the user.
     */
    suspend fun checkForUpdates(): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersionName()
        val currentCode = getCurrentVersionCode()
        try {
            val baseUrl = UpdateSourceResolver.getBaseUrl(context)
                ?: return@withContext Result.failure(IOException("No update source available"))

            val request = Request.Builder()
                .url("$baseUrl/$APP_VERSION_FILE")
                .header("User-Agent", EngineUpdateManager.USER_AGENT)
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Update source returned HTTP ${response.code}"))
                }
                response.body?.string().orEmpty()
            }
            if (body.isBlank()) {
                return@withContext Result.failure(IOException("Empty app_version.json"))
            }

            val json = JSONObject(body)
            val remoteCode = json.optLong("versionCode", 0L)
            val remoteName = json.optString("versionName", "").trim()
            val apkUrl = json.optString("apkUrl", "").trim()
            val notes = json.optString("releaseNotes", "")
            val force = json.optBoolean("forceUpdate", false)

            if (remoteCode <= 0L && remoteName.isBlank()) {
                return@withContext Result.failure(IOException("Invalid app_version.json"))
            }
            // Only https APK links are ever offered to the user.
            val safeApkUrl = if (apkUrl.startsWith("https://", ignoreCase = true)) apkUrl else ""

            // The source answered with a valid document: remember it as the last good one.
            UpdateSourceResolver.markValid(context, baseUrl)

            val isNewer = if (remoteCode > 0L && currentCode > 0L) {
                remoteCode > currentCode
            } else {
                isVersionNewer(latest = remoteName, current = currentVersion)
            }

            val updateInfo = AppUpdateInfo(
                hasUpdate = isNewer && safeApkUrl.isNotBlank(),
                latestVersion = remoteName.removePrefix("v"),
                currentVersion = currentVersion.removePrefix("v"),
                releaseTitle = if (remoteName.isNotBlank()) "v${remoteName.removePrefix("v")}" else "",
                releaseNotes = notes,
                apkDownloadUrl = safeApkUrl,
                releasePageUrl = json.optString("releasePageUrl", safeApkUrl),
                fileSize = json.optLong("fileSize", 0L),
                forceUpdate = force
            )

            Log.d(tag, "Checked update: current=$currentVersion($currentCode), latest=$remoteName($remoteCode), hasUpdate=${updateInfo.hasUpdate}")
            Result.success(updateInfo)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(tag, "Update check skipped: ${e.message}")
            Result.failure(e)
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        if (latest.isBlank()) return false
        val cleanLatest = latest.lowercase().removePrefix("v").trim()
        val cleanCurrent = current.lowercase().removePrefix("v").trim()

        if (cleanLatest == cleanCurrent) return false

        try {
            val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {
            return cleanLatest != cleanCurrent
        }

        return false
    }

    fun downloadApkStream(downloadUrl: String): Flow<UpdateDownloadState> = flow {
        emit(UpdateDownloadState.Downloading(0, 0, -1L))

        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val destinationFile = File(downloadDir, "media-download-update.apk")
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Android) MediaDownloadApp/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                emit(UpdateDownloadState.Error("HTTP download error: ${response.code}"))
                return@flow
            }

            val body = response.body
            if (body == null) {
                emit(UpdateDownloadState.Error("Response body is empty"))
                return@flow
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastPercent = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }

                        if (percent != lastPercent || percent == 100) {
                            lastPercent = percent
                            emit(UpdateDownloadState.Downloading(percent, downloadedBytes, totalBytes))
                        }
                    }
                    output.flush()
                }
            }

            emit(UpdateDownloadState.ReadyToInstall(destinationFile))
        } catch (e: Exception) {
            emit(UpdateDownloadState.Error(e.message ?: "Failed to download update APK"))
        }
    }.flowOn(Dispatchers.IO)

    fun launchPackageInstaller(apkFile: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }

    private companion object {
        const val APP_VERSION_FILE = "app_version.json"
    }
}
