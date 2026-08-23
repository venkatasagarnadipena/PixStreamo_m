/**
 * PixStreamo Configuration Preferences
 *
 * Manages small-scale persistent settings using SharedPreferences.
 * Responsible for storing the Master URL and Cache Storage preferences.
 */
package com.example.pixstreamo_m.data

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pixstreamo_prefs", Context.MODE_PRIVATE)

    fun getConfigUrl(): String? = prefs.getString("config_url", null)
    fun setConfigUrl(url: String) = prefs.edit().putString("config_url", url).apply()

    fun getCacheMode(): String = prefs.getString("cache_mode", "AUTO") ?: "AUTO"
    fun setCacheMode(mode: String) = prefs.edit().putString("cache_mode", mode).apply()

    fun getCacheUri(): String? = prefs.getString("cache_uri", null)
    fun setCacheUri(uri: String?) = prefs.edit().putString("cache_uri", uri).apply()

    fun isConfigured(): Boolean = getConfigUrl() != null
    fun clearConfig() = prefs.edit().remove("config_url").apply()
}
