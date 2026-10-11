/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.apps

import android.content.Context

/** Stores the two most recently opened extensions shown in the bottom toolbar. */
class ExtensionPinsRepository(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Moves an extension to the front and retains only the two newest extensions. */
    fun touch(extensionId: String) {
        val ids = getOrderedIds()
            .filterNot { it == extensionId }
            .toMutableList()
            .apply { add(0, extensionId) }
            .take(MAX_VISIBLE_EXTENSIONS)

        preferences
            .edit()
            .putString(KEY_RECENT_ORDER, ids.joinToString(SEPARATOR))
            .remove(KEY_LEGACY_PINNED)
            .apply()
    }

    fun getPinnedIds(): Set<String> = getOrderedIds().take(MAX_VISIBLE_EXTENSIONS).toSet()

    private fun getOrderedIds(): List<String> {
        if (preferences.contains(KEY_RECENT_ORDER)) {
            return preferences
                .getString(KEY_RECENT_ORDER, "")
                .orEmpty()
                .split(SEPARATOR)
                .filter(String::isNotBlank)
        }

        // Migrate the unordered set used by the previous switch-based UI.
        return preferences.getStringSet(KEY_LEGACY_PINNED, emptySet()).orEmpty().toList()
    }

    companion object {
        private const val PREFERENCES = "firefoxlite_extensions"
        private const val KEY_RECENT_ORDER = "recent_extensions_order"
        private const val KEY_LEGACY_PINNED = "pinned_extensions"
        private const val SEPARATOR = "\u001F"
        private const val MAX_VISIBLE_EXTENSIONS = 2
    }
}
