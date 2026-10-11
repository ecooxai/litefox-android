/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.apps

data class SavedApp(
    val url: String,
    val title: String,
    val tabId: String? = null,
    val iconData: String? = null,
)
