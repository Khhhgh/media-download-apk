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
import com.example.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
    private val repoReleasesUrl = "https://api.github.com/repos/Khhhgh/media-download-apk/releases/latest"

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    suspend fun checkForUpdates(): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersionName()
        try {
            val request = Request.Builder()
                .url(repoReleasesUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "MediaDownloadApp-UpdateChecker/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("GitHub API returned HTTP ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            if (body.isBlank()) {
                return@withContext Result.failure(IOException("Empty release response"))
            }

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            val releaseTitle = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/Khhhgh/media-download-apk/releases")

            var apkUrl = ""
            var fileSize = 0L

            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                        apkUrl = downloadUrl
                        fileSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            // If no asset matched .apk directly, fallback to standard GitHub release asset URL
            if (apkUrl.isBlank() && tagName.isNotBlank()) {
                apkUrl = "https://github.com/Khhhgh/media-download-apk/releases/download/$tagName/media-download-update.apk"
            }

            val isNewer = isVersionNewer(latest = tagName, current = currentVersion)

            val updateInfo = AppUpdateInfo(
                hasUpdate = isNewer,
                latestVersion = tagName.removePrefix("v"),
                currentVersion = currentVersion.removePrefix("v"),
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                apkDownloadUrl = apkUrl,
                releasePageUrl = htmlUrl,
                fileSize = fileSize
            )

            Log.d(tag, "Checked update: current=$currentVersion, latest=$tagName, hasUpdate=$isNewer")
            Result.success(updateInfo)
        } catch (e: Exception) {
            Log.w(tag, "Failed to check for update: ${e.message}")
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
}
