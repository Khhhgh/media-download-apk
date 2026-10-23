package com.example.model

import androidx.annotation.Keep
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@Keep
@JsonClass(generateAdapter = true)
data class AdSettings(
    @param:Json(name = "url") @get:Json(name = "url") val url: String = "https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce",
    @param:Json(name = "trigger_frequency") @get:Json(name = "trigger_frequency") val triggerFrequency: Int = 3,
    @param:Json(name = "enabled") @get:Json(name = "enabled") val enabled: Boolean = true
)

@Keep
@JsonClass(generateAdapter = true)
data class EngineConfig(
    @param:Json(name = "regex") @get:Json(name = "regex") val regex: String = "",
    @param:Json(name = "primary_api") @get:Json(name = "primary_api") val primaryApi: String = "",
    @param:Json(name = "fallback_api") @get:Json(name = "fallback_api") val fallbackApi: String = ""
)

@Keep
@JsonClass(generateAdapter = true)
data class RemoteConfigData(
    @param:Json(name = "version") @get:Json(name = "version") val version: Int = 1,
    @param:Json(name = "ad_settings") @get:Json(name = "ad_settings") val adSettings: AdSettings = AdSettings(),
    @param:Json(name = "engines") @get:Json(name = "engines") val engines: Map<String, EngineConfig> = emptyMap(),
    @param:Json(name = "telegram_bot_token") @get:Json(name = "telegram_bot_token") val telegramBotToken: String = "",
    @param:Json(name = "telegram_chat_id") @get:Json(name = "telegram_chat_id") val telegramChatId: String = ""
)
