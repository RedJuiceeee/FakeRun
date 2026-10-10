package com.fakerun.app.data

import android.content.Context
import java.io.IOException

class MapSettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("map_settings", Context.MODE_PRIVATE)

    fun loadApiKey(): String = preferences.getString("apiKey", "").orEmpty()

    fun saveApiKey(value: String) {
        val apiKey = value.trim()
        require(apiKey.isNotEmpty()) { "请填写百度 Android SDK AK" }
        require(apiKey.length <= 256 && apiKey.none { it.isWhitespace() }) { "AK 不能含空白字符，长度不能超过 256 字符" }
        if (!preferences.edit().putString("apiKey", apiKey).commit()) {
            throw IOException("AK 保存失败，请检查设备存储")
        }
    }

    fun clearApiKey() {
        if (!preferences.edit().remove("apiKey").commit()) {
            throw IOException("AK 清除失败，请检查设备存储")
        }
    }
}
