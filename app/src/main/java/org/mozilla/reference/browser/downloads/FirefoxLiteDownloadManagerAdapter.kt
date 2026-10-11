/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.manager.DownloadManager

/**
 * Connects Android Components' download confirmation flow to FirefoxLite's
 * persisted downloader. This keeps confirmed browser downloads in the same
 * Downloads tab as URLs entered there manually.
 */
class FirefoxLiteDownloadManagerAdapter(
    private val manager: FirefoxLiteDownloadManager,
) : DownloadManager {
    private val taskIds = mutableMapOf<String, String>()

    override var onDownloadStopped: (DownloadState, String, DownloadState.Status) -> Unit =
        { _, _, _ -> }

    override val permissions: Array<String> = emptyArray()

    override fun download(download: DownloadState, cookie: String): String? {
        val taskId = runCatching {
            manager.start(
                url = download.url,
                requestedThreads = manager.preferredThreadCount(),
                suggestedFileName = download.fileName,
                contentDisposition = headerValue(download, "Content-Disposition"),
                contentLocation = headerValue(download, "Content-Location"),
                contentType = headerValue(download, "Content-Type"),
                userAgent = download.userAgent,
                referrerUrl = download.referrerUrl,
            )
        }.getOrNull() ?: return null
        synchronized(taskIds) {
            taskIds[download.id] = taskId
        }
        return taskId
    }

    override fun tryAgain(downloadId: String) {
        val taskId = synchronized(taskIds) { taskIds[downloadId] } ?: downloadId
        manager.resume(taskId)
    }

    private fun headerValue(download: DownloadState, name: String): String? {
        val headers = download.response?.headers ?: return null
        return headers.getAll(name).firstOrNull { it.isNotBlank() }
            ?: headers.get(name)?.takeIf { it.isNotBlank() }
    }

}
