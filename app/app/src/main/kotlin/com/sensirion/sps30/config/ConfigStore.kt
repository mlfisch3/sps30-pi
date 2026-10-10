package com.sensirion.sps30.config

import android.content.Context
import com.sensirion.sps30.data.AppConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the live [AppConfig] plus any number of named saved configs in
 * SharedPreferences (as JSON). Robust to missing/corrupt entries - they fall
 * back to defaults rather than throwing.
 */
object ConfigStore {
    private const val PREFS = "sps30_config"
    private const val KEY_CURRENT = "current"
    private const val KEY_NAMES = "saved_names"
    private const val PREFIX_SAVED = "saved:"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadCurrent(context: Context): AppConfig {
        val raw = prefs(context).getString(KEY_CURRENT, null) ?: return AppConfig.default()
        return runCatching { json.decodeFromString<AppConfig>(raw) }.getOrDefault(AppConfig.default())
    }

    fun saveCurrent(context: Context, config: AppConfig) {
        prefs(context).edit().putString(KEY_CURRENT, json.encodeToString(config)).apply()
    }

    fun savedNames(context: Context): List<String> =
        prefs(context).getStringSet(KEY_NAMES, emptySet()).orEmpty().sorted()

    fun saveNamed(context: Context, name: String, config: AppConfig) {
        val key = name.trim()
        if (key.isEmpty()) return
        val names = savedNames(context).toMutableSet().apply { add(key) }
        prefs(context).edit()
            .putString(PREFIX_SAVED + key, json.encodeToString(config))
            .putStringSet(KEY_NAMES, names)
            .apply()
    }

    fun loadNamed(context: Context, name: String): AppConfig? {
        val raw = prefs(context).getString(PREFIX_SAVED + name, null) ?: return null
        return runCatching { json.decodeFromString<AppConfig>(raw) }.getOrNull()
    }

    fun deleteNamed(context: Context, name: String) {
        val names = savedNames(context).toMutableSet().apply { remove(name) }
        prefs(context).edit()
            .remove(PREFIX_SAVED + name)
            .putStringSet(KEY_NAMES, names)
            .apply()
    }
}
