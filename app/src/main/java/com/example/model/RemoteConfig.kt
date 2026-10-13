package com.example.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AdSettings(
    @Json(name = "url") val url: String = "https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce",
    @Json(name = "trigger_frequency") val triggerFrequency: Int = 3,
    @Json(name = "enabled") val enabled: Boolean = true
)

@JsonClass(generateAdapter = true)
data class EngineConfig(
    @Json(name = "regex") val regex: String = "",
    @Json(name = "primary_api") val primaryApi: String = "",
    @Json(name = "fallback_api") val fallbackApi: String = ""
)

@JsonClass(generateAdapter = true)
data class RemoteConfigData(
    @Json(name = "version") val version: Int = 1,
    @Json(name = "ad_settings") val adSettings: AdSettings = AdSettings(),
    @Json(name = "engines") val engines: Map<String, EngineConfig> = emptyMap()
)
