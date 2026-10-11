/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.min

/** Hosts the local HTML frontend for the persisted native download manager. */
class DownloadManagerView(
    context: Context,
    private val manager: FirefoxLiteDownloadManager,
    private val onDownloadStarted: () -> Unit,
) : FrameLayout(context) {
    private val webView = WebView(context)

    init {
        setBackgroundColor(Color.rgb(18, 20, 24))
        webView.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.rgb(18, 20, 24))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    return interceptDownloadRequest(request) ?: super.shouldInterceptRequest(view, request)
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(DownloadBridge(), "FirefoxLiteDownloads")
            loadUrl("file:///android_asset/firefoxlite_downloads.html")
        }
        addView(webView)
    }

    fun release() {
        webView.stopLoading()
        webView.removeJavascriptInterface("FirefoxLiteDownloads")
        webView.destroy()
    }

    private fun interceptDownloadRequest(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host != DOWNLOAD_HOST) return null
        val segments = uri.pathSegments
        val id = segments.getOrNull(1) ?: return null
        return when (segments.firstOrNull()) {
            "file" -> fileResponse(id, request.requestHeaders.rangeHeader())
            "pdf-preview" -> pdfPreviewResponse(id)
            else -> null
        }
    }

    private fun fileResponse(id: String, rangeHeader: String?): WebResourceResponse? {
        val info = manager.fileInfoFor(id) ?: return null
        val input = manager.openDownloadedInputStream(id) ?: return null
        val range = parseRange(rangeHeader, info.length)
        if (range == null) {
            return WebResourceResponse(info.mimeType, null, input).apply {
                responseHeaders = buildMap {
                    put("Accept-Ranges", "bytes")
                    if (info.length >= 0L) put("Content-Length", info.length.toString())
                }
            }
        }

        if (!skipFully(input, range.first)) {
            input.close()
            return null
        }
        val contentLength = range.last - range.first + 1L
        return WebResourceResponse(
            info.mimeType,
            null,
            LimitedInputStream(input, contentLength),
        ).apply {
            setStatusCodeAndReasonPhrase(206, "Partial Content")
            responseHeaders = mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to contentLength.toString(),
                "Content-Range" to "bytes ${range.first}-${range.last}/${info.length}",
            )
        }
    }

    private fun pdfPreviewResponse(id: String): WebResourceResponse? {
        val bytes = manager.pdfPreviewPng(id) ?: return null
        return WebResourceResponse("image/png", null, ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf(
                "Cache-Control" to "no-store",
                "Content-Length" to bytes.size.toString(),
            )
        }
    }

    private fun parseRange(header: String?, length: Long): LongRange? {
        if (header.isNullOrBlank() || length <= 0L || !header.startsWith("bytes=")) return null
        val value = header.substringAfter('=').substringBefore(',').trim()
        val dash = value.indexOf('-')
        if (dash < 0) return null
        val startText = value.substring(0, dash).trim()
        val endText = value.substring(dash + 1).trim()
        if (startText.isEmpty()) {
            // Media engines commonly request the tail of a file to read a
            // container index. Treat bytes=-N as a suffix range instead of
            // accidentally returning the whole file from byte zero.
            val suffixLength = endText.toLongOrNull() ?: return null
            if (suffixLength <= 0L) return null
            val actualLength = suffixLength.coerceAtMost(length)
            return (length - actualLength)..(length - 1L)
        }
        val start = startText.toLongOrNull() ?: return null
        if (start < 0L || start >= length) return null
        val end = endText.toLongOrNull()?.coerceAtMost(length - 1L) ?: (length - 1L)
        return if (end >= start) start..end else null
    }

    private fun skipFully(input: InputStream, bytes: Long): Boolean {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() == -1) {
                return false
            } else {
                remaining--
            }
        }
        return true
    }

    private fun openWithExternalApp(id: String): String {
        return runCatching {
            val info = manager.fileInfoFor(id) ?: error("File is not ready")
            val uri = shareableUri(info.uri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, info.mimeType)
                clipData = ClipData.newRawUri(info.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val chooser = Intent.createChooser(intent, "Open with").apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            "OK"
        }.getOrElse { error ->
            "ERROR:${error.message ?: "No installed app can open this file"}"
        }
    }

    private fun shareableUri(uri: Uri): Uri {
        if (!uri.scheme.equals("file", ignoreCase = true)) return uri
        val file = File(uri.path ?: error("Downloaded file path is missing"))
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.downloads.provider",
            file,
        )
    }

    private inner class DownloadBridge {
        @JavascriptInterface
        fun state(): String = manager.stateJson()

        @JavascriptInterface
        fun preferredThreads(): Int = manager.preferredThreadCount()

        @JavascriptInterface
        fun setPreferredThreads(threads: Int): Int = manager.setPreferredThreadCount(threads)

        @JavascriptInterface
        fun start(url: String, threads: Int): String {
            return runCatching {
                val id = manager.start(url, threads)
                post { onDownloadStarted() }
                id
            }.getOrElse { error ->
                "ERROR:${error.message.orEmpty()}"
            }
        }

        @JavascriptInterface
        fun pause(id: String) = manager.pause(id)

        @JavascriptInterface
        fun resume(id: String) = manager.resume(id)

        @JavascriptInterface
        fun restart(id: String) = manager.restart(id)

        @JavascriptInterface
        fun delete(id: String) = manager.delete(id)

        @JavascriptInterface
        fun rename(id: String, name: String): String {
            return runCatching { manager.rename(id, name) }
                .getOrElse { error -> "ERROR:${error.message ?: "Could not rename file"}" }
        }

        @JavascriptInterface
        fun copyUrl(id: String) {
            val url = manager.urlFor(id) ?: return
            post {
                val clipboard = this@DownloadManagerView.context
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Download URL", url))
                Toast.makeText(this@DownloadManagerView.context, "Download URL copied", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun fileInfo(id: String): String {
            val info = manager.fileInfoFor(id) ?: return ""
            val kind = when {
                info.mimeType == "application/pdf" -> "pdf"
                info.mimeType.startsWith("image/") -> "image"
                info.mimeType.startsWith("audio/") -> "audio"
                info.mimeType.startsWith("video/") -> "video"
                isTextMimeType(info.mimeType) -> "text"
                else -> "external"
            }
            return JSONObject().apply {
                put("id", info.id)
                put("fileName", info.fileName)
                put("mimeType", info.mimeType)
                put("length", info.length)
                put("kind", kind)
                put("resourceUrl", "$RESOURCE_ORIGIN/file/${Uri.encode(info.id)}")
                put("pdfPreviewUrl", "$RESOURCE_ORIGIN/pdf-preview/${Uri.encode(info.id)}")
            }.toString()
        }

        @JavascriptInterface
        fun textPreview(id: String): String = manager.textPreview(id).orEmpty()

        @JavascriptInterface
        fun openExternal(id: String): String = openWithExternalApp(id)
    }

    private fun isTextMimeType(mimeType: String): Boolean =
        mimeType.startsWith("text/") || mimeType in setOf(
            "application/json",
            "application/javascript",
            "application/xml",
            "application/xhtml+xml",
            "application/rtf",
        )

    private class LimitedInputStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val requested = min(length.toLong(), remaining).toInt()
            val count = super.read(buffer, offset, requested)
            if (count > 0) remaining -= count
            return count
        }
    }

    private fun Map<String, String>.rangeHeader(): String? =
        entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value

    private companion object {
        const val DOWNLOAD_HOST = "download.firefoxlite.local"
        const val RESOURCE_ORIGIN = "https://$DOWNLOAD_HOST"
    }
}
