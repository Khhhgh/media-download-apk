package com.example.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Single source of truth for the on-disk locations of the dynamic Python engines.
 *
 * Layout (all under the app's private storage, resolved dynamically from [Context.filesDir];
 * no hard-coded "/data/data/..." paths anywhere):
 *
 *   filesDir/active_engines/      <- active engine tree (bundled or OTA-updated)
 *   filesDir/active_engines.bak/  <- previous known-good tree, kept until an update succeeds
 *   filesDir/site-packages/       <- dynamically installed pure-Python packages
 *   cacheDir/temp_engines/        <- staging area for OTA downloads
 */
object EngineStorage {
    private const val TAG = "EngineStorage"
    const val ENGINE_VERSION_FILE = "version.txt"
    const val ENGINE_MANIFEST_FILE = "manifest.json"
    const val ASSET_ENGINES_ROOT = "engines"

    fun activeEnginesDir(context: Context): File = File(context.filesDir, "active_engines")
    fun backupEnginesDir(context: Context): File = File(context.filesDir, "active_engines.bak")
    fun sitePackagesDir(context: Context): File = File(context.filesDir, "site-packages")
    fun stagingDir(context: Context): File = File(context.cacheDir, "temp_engines")
    fun lockFile(context: Context): File = File(context.filesDir, ".engine_update.lock")

    /** Reads the locally active engine version, or 0 if none is installed. */
    fun readActiveVersion(context: Context): Int {
        val file = File(activeEnginesDir(context), ENGINE_VERSION_FILE)
        if (!file.exists()) return 0
        return try {
            file.readText().trim().toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read active engine version: ${e.message}")
            0
        }
    }

    fun isInstalled(context: Context): Boolean {
        val dir = activeEnginesDir(context)
        return dir.isDirectory && File(dir, "runner.py").isFile && readActiveVersion(context) > 0
    }

    /** Recursively copies the bundled `assets/engines/` tree into a destination directory. */
    @Throws(IOException::class)
    fun copyBundledEngines(context: Context, destination: File) {
        if (destination.exists()) destination.deleteRecursively()
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IOException("Unable to create engine directory: ${destination.absolutePath}")
        }
        copyAssetDir(context, ASSET_ENGINES_ROOT, destination)
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val assets = context.assets
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // Leaf file: stream it out.
            destDir.parentFile?.let { if (!it.exists()) it.mkdirs() }
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        for (child in children) {
            if (child == "__pycache__" || child.endsWith(".pyc")) continue
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)
            val grandChildren = assets.list(childAssetPath) ?: emptyArray()
            if (grandChildren.isEmpty()) {
                if (!destDir.exists()) destDir.mkdirs()
                assets.open(childAssetPath).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                if (!childDest.exists()) childDest.mkdirs()
                copyAssetDir(context, childAssetPath, childDest)
            }
        }
    }

    /** Installs the engines bundled in the APK the very first time the app runs. */
    fun installBundledIfNeeded(context: Context): Boolean {
        if (isInstalled(context)) return false
        return try {
            copyBundledEngines(context, activeEnginesDir(context))
            Log.i(TAG, "Bundled engines installed at ${activeEnginesDir(context).absolutePath} (v${readActiveVersion(context)})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install bundled engines", e)
            false
        }
    }
}
