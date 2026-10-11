/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.Settings as EngineSettings
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.reference.browser.R

data class PreviewSearchEngine(
    val id: String,
    val name: String,
    val urlPrefix: String,
    val iconLabel: String,
)

object Settings {
    const val DNS_MODE_NORMAL = "normal"
    const val DNS_MODE_CLOUDFLARE = "cloudflare"
    private const val CLOUDFLARE_DOH_URL = "https://cloudflare-dns.com/dns-query"
    private const val PREVIEW_SEARCH_ENGINES = "firefoxlite_preview_search_engines"
    private const val PREVIEW_DEFAULT_SEARCH_ENGINE = "firefoxlite_preview_default_search_engine"

    private val builtInPreviewSearchEngines = listOf(
        PreviewSearchEngine("google", "Google", "https://www.google.com/search?q=", "G"),
        PreviewSearchEngine("bing", "Bing", "https://www.bing.com/search?q=", "b"),
        PreviewSearchEngine("chatgpt", "ChatGPT", "https://chatgpt.com/?q=", "✦"),
        PreviewSearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=", "D"),
    )

    fun isPullToRefreshEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
            context.getString(R.string.pref_key_pull_to_refresh),
            false,
        )

    fun isForceZoomEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
            context.getString(R.string.pref_key_force_zoom),
            false,
        )

    fun getDnsMode(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(
            context.getString(R.string.pref_key_dns_mode),
            DNS_MODE_CLOUDFLARE,
        ) ?: DNS_MODE_CLOUDFLARE

    fun getPreviewSearchEngines(context: Context): List<PreviewSearchEngine> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val custom = runCatching {
            val values = JSONArray(preferences.getString(PREVIEW_SEARCH_ENGINES, "[]"))
            buildList {
                for (index in 0 until values.length()) {
                    val item = values.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name").trim()
                    val urlPrefix = item.optString("urlPrefix").trim()
                    if (id.isBlank() || name.isBlank() || !isHttpUrl(urlPrefix)) continue
                    add(
                        PreviewSearchEngine(
                            id = id,
                            name = name,
                            urlPrefix = urlPrefix,
                            iconLabel = name.first().uppercase(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        return builtInPreviewSearchEngines + custom
    }

    fun getDefaultPreviewSearchEngine(context: Context): PreviewSearchEngine {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val defaultId = preferences.getString(PREVIEW_DEFAULT_SEARCH_ENGINE, "google")
        return getPreviewSearchEngines(context).firstOrNull { it.id == defaultId }
            ?: builtInPreviewSearchEngines.first()
    }

    fun setDefaultPreviewSearchEngine(context: Context, engineId: String) {
        if (getPreviewSearchEngines(context).none { it.id == engineId }) return
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREVIEW_DEFAULT_SEARCH_ENGINE, engineId)
        }
    }

    fun addPreviewSearchEngine(
        context: Context,
        name: String,
        urlPrefix: String,
    ): Boolean {
        val cleanName = name.trim()
        val cleanUrl = urlPrefix.trim()
        if (cleanName.isBlank() || !isHttpUrl(cleanUrl)) return false

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val values = runCatching {
            JSONArray(preferences.getString(PREVIEW_SEARCH_ENGINES, "[]"))
        }.getOrDefault(JSONArray())
        val id = "custom-${cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-${cleanUrl.hashCode()}"
        for (index in 0 until values.length()) {
            if (values.optJSONObject(index)?.optString("id") == id) return false
        }
        values.put(
            JSONObject().apply {
                put("id", id)
                put("name", cleanName)
                put("urlPrefix", cleanUrl)
            },
        )
        preferences.edit { putString(PREVIEW_SEARCH_ENGINES, values.toString()) }
        return true
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    fun applyDnsMode(
        engineSettings: EngineSettings,
        mode: String,
    ) {
        if (mode == DNS_MODE_NORMAL) {
            engineSettings.dohSettingsMode = Engine.DohSettingsMode.OFF
        } else {
            engineSettings.dohProviderUrl = CLOUDFLARE_DOH_URL
            engineSettings.dohDefaultProviderUrl = CLOUDFLARE_DOH_URL
            engineSettings.dohSettingsMode = Engine.DohSettingsMode.INCREASED
        }
    }

    fun isSystemBarsHidden(context: Context): Boolean =
        isStatusBarHidden(context) || isNavigationBarHidden(context)

    fun isStatusBarHidden(context: Context): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getBoolean(
            context.getString(R.string.pref_key_hide_status_bar),
            preferences.getBoolean(context.getString(R.string.pref_key_hide_system_bars), false),
        )
    }

    fun isNavigationBarHidden(context: Context): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getBoolean(
            context.getString(R.string.pref_key_hide_navigation_bar),
            preferences.getBoolean(context.getString(R.string.pref_key_hide_system_bars), false),
        )
    }

    fun isTelemetryEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
            context.getString(R.string.pref_key_telemetry),
            true,
        )

    fun getOverrideAmoUser(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(
            context.getString(R.string.pref_key_override_amo_user),
            "",
        ) ?: ""

    fun getOverrideAmoCollection(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(
            context.getString(R.string.pref_key_override_amo_collection),
            "",
        ) ?: ""

    fun setOverrideAmoUser(
        context: Context,
        value: String,
    ) {
        val key = context.getString(R.string.pref_key_override_amo_user)
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(key, value)
        }
    }

    fun setOverrideAmoCollection(
        context: Context,
        value: String,
    ) {
        val key = context.getString(R.string.pref_key_override_amo_collection)
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(key, value)
        }
    }

    fun isAmoCollectionOverrideConfigured(context: Context): Boolean =
        getOverrideAmoUser(context).isNotEmpty() && getOverrideAmoCollection(context).isNotEmpty()
}
