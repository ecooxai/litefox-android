/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class SavedAppsRepository(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Suppress("ktlint:standard:function-expression-body")
    fun getAll(): List<SavedApp> {
        return runCatching {
            val array = JSONArray(preferences.getString(KEY_APPS, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString(KEY_URL)
                    if (url.isBlank()) continue
                    add(
                        SavedApp(
                            url = url,
                            title = item.optString(KEY_TITLE).ifBlank { Uri.parse(url).host ?: url },
                            tabId = item.optString(KEY_TAB_ID).takeIf { it.isNotBlank() },
                            iconData = item.optString(KEY_ICON).takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addOrUpdate(
        url: String,
        title: String,
        tabId: String?,
    ) {
        if (url.isBlank() || url == "about:blank" || url.startsWith("about:private")) return

        val key = normalize(url)
        val apps = getAll().toMutableList()
        val index = apps.indexOfFirst { normalize(it.url) == key }
        val previous = apps.getOrNull(index)
        val app = SavedApp(
            url = url,
            title = title.ifBlank { Uri.parse(url).host ?: url },
            tabId = tabId ?: previous?.tabId,
            iconData = previous?.iconData,
        )
        if (index >= 0) {
            apps[index] = app
        } else {
            apps.add(app)
        }
        save(apps)
    }

    fun bindTab(
        url: String,
        tabId: String,
    ) {
        val key = normalize(url)
        save(getAll().map { app -> if (normalize(app.url) == key) app.copy(tabId = tabId) else app })
    }

    fun clearTabBinding(url: String) {
        val key = normalize(url)
        save(getAll().map { app -> if (normalize(app.url) == key) app.copy(tabId = null) else app })
    }

    fun updateIcon(
        url: String,
        iconData: String,
    ) {
        if (iconData.isBlank()) return
        val key = normalize(url)
        save(getAll().map { app -> if (normalize(app.url) == key) app.copy(iconData = iconData) else app })
    }

    fun update(
        original: SavedApp,
        title: String,
        url: String,
    ): Boolean {
        val cleanUrl = url.trim()
        if (!isAllowedAppUrl(cleanUrl)) return false

        val apps = getAll().toMutableList()
        val index = apps.indexOfFirst { normalize(it.url) == normalize(original.url) }
        if (index < 0) return false

        val newKey = normalize(cleanUrl)
        if (apps.indices.any { it != index && normalize(apps[it].url) == newKey }) return false

        val previous = apps[index]
        apps[index] = SavedApp(
            url = cleanUrl,
            title = title.trim().ifBlank { Uri.parse(cleanUrl).host ?: cleanUrl },
            tabId = previous.tabId.takeIf { normalize(previous.url) == newKey },
            iconData = previous.iconData.takeIf { normalize(previous.url) == newKey },
        )
        save(apps)
        return true
    }

    fun remove(url: String) {
        val key = normalize(url)
        save(getAll().filterNot { normalize(it.url) == key })
    }

    private fun save(apps: List<SavedApp>) {
        val array = JSONArray()
        apps.forEach { app ->
            array.put(
                JSONObject().apply {
                    put(KEY_URL, app.url)
                    put(KEY_TITLE, app.title)
                    app.tabId?.let { put(KEY_TAB_ID, it) }
                    app.iconData?.let { put(KEY_ICON, it) }
                },
            )
        }
        preferences.edit().putString(KEY_APPS, array.toString()).apply()
    }

    private fun isAllowedAppUrl(url: String): Boolean =
        url.isNotBlank() && url != "about:blank" && !url.startsWith("about:private")

    companion object {
        private const val PREFERENCES = "firefoxlite_apps"
        private const val KEY_APPS = "saved_apps"
        private const val KEY_URL = "url"
        private const val KEY_TITLE = "title"
        private const val KEY_TAB_ID = "tab_id"
        private const val KEY_ICON = "icon_data"
        private const val MAX_ICON_BYTES = 128 * 1024

        @Suppress("ktlint:standard:function-expression-body")
        fun normalize(url: String): String {
            return runCatching {
                Uri
                    .parse(url)
                    .buildUpon()
                    .fragment(null)
                    .build()
                    .toString()
                    .trimEnd('/')
            }.getOrDefault(url.trimEnd('/'))
        }

        fun host(url: String): String {
            val parsedHost = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
            if (parsedHost.isNotBlank()) return parsedHost.lowercase()
            return url
                .substringBefore('/')
                .substringAfterLast('@')
                .substringBefore(':')
                .lowercase()
        }

        fun encodeIcon(bitmap: Bitmap): String? {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
            val bytes = output.toByteArray()
            if (bytes.size > MAX_ICON_BYTES) return null
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        fun decodeIcon(iconData: String?): Bitmap? {
            if (iconData.isNullOrBlank()) return null
            return runCatching {
                val bytes = Base64.decode(iconData, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
}
