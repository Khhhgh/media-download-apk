package com.example.data

import android.content.Context
import android.util.Log
import com.example.model.RemoteConfigData
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemoteConfigRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val tag = "RemoteConfigRepo"
    private val prefs = context.getSharedPreferences("remote_config_prefs", Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(RemoteConfigData::class.java)

    // Remote GitHub Raw URL
    var customRemoteUrl: String
        get() = prefs.getString(
            KEY_REMOTE_URL,
            DEFAULT_GITHUB_RAW_URL
        ) ?: DEFAULT_GITHUB_RAW_URL
        set(value) = prefs.edit().putString(KEY_REMOTE_URL, value).apply()

    private val _configState = MutableStateFlow<RemoteConfigData>(RemoteConfigData())
    val configState: StateFlow<RemoteConfigData> = _configState.asStateFlow()

    init {
        loadInitialConfig()
    }

    private fun loadInitialConfig() {
        // 1. Try SharedPreferences cache
        val cachedJson = prefs.getString(KEY_CACHED_CONFIG, null)
        if (!cachedJson.isNullOrBlank()) {
            try {
                val cached = adapter.fromJson(cachedJson)
                if (cached != null) {
                    _configState.value = cached
                    Log.d(tag, "Loaded remote config from cache")
                    return
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to parse cached config", e)
            }
        }

        // 2. Fallback to bundled asset
        try {
            val assetJson = context.assets.open("remote_config.json").bufferedReader().use { it.readText() }
            val bundled = adapter.fromJson(assetJson)
            if (bundled != null) {
                _configState.value = bundled
                Log.d(tag, "Loaded fallback remote config from bundled assets")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to read bundled asset remote_config.json", e)
        }
    }

    suspend fun fetchRemoteConfig(): Result<RemoteConfigData> = withContext(Dispatchers.IO) {
        val targetUrl = customRemoteUrl
        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "MediaDownload-Engine/1.0")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Server error: HTTP ${response.code}"))
            }

            val responseBody = response.body?.string() ?: ""
            val parsed = adapter.fromJson(responseBody)
                ?: return@withContext Result.failure(IOException("Unable to parse config schema"))

            // Cache successfully fetched config
            prefs.edit().putString(KEY_CACHED_CONFIG, responseBody).apply()
            _configState.value = parsed
            Log.d(tag, "Successfully refreshed remote config from $targetUrl")
            Result.success(parsed)
        } catch (e: Exception) {
            Log.w(tag, "Failed to fetch remote config, keeping active fallback", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val KEY_CACHED_CONFIG = "cached_config_json"
        private const val KEY_REMOTE_URL = "custom_remote_url"
        const val DEFAULT_GITHUB_RAW_URL =
            "https://gist.githubusercontent.com/Khhhgh/ba31c38a8b3e3e5b3c4b7998e5cd3c44/raw/engines.json"
    }
}
