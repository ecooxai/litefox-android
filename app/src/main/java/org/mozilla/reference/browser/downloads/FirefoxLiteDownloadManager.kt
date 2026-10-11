/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Headers
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A small, persisted HTTP downloader for the FirefoxLite Downloads tab.
 *
 * Each ranged task is split into independent byte segments. Segment progress is
 * written to the app's private storage so a paused task can be resumed after
 * the process is recreated. Servers that do not honor Range are handled by a
 * single resumable stream instead of failing the task.
 */
class FirefoxLiteDownloadManager(
    context: Context,
    private val httpClient: Client? = null,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val storageDirectory = File(appContext.filesDir, STORAGE_DIRECTORY).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val tasks = LinkedHashMap<String, DownloadTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()
    private val responses = ConcurrentHashMap<String, MutableSet<Response>>()
    /**
     * IDs kept here until their worker has fully stopped. This closes the race
     * where a cancelled worker could recreate a .part file after the task was
     * removed from the persisted task list.
     */
    private val deleteRequestedIds = ConcurrentHashMap.newKeySet<String>()
    private val statusListeners = CopyOnWriteArrayList<() -> Unit>()
    private val lastPersistAt = mutableMapOf<String, Long>()

    init {
        loadTasks()
    }

    fun stateJson(): String = synchronized(lock) {
        JSONArray().apply {
            tasks.values.forEach { task ->
                put(
                    JSONObject().apply {
                        put("id", task.id)
                        put("url", task.url)
                        put("fileName", task.fileName)
                        put("state", task.state)
                        put("totalBytes", task.totalBytes)
                        put("downloadedBytes", task.downloadedBytes)
                        put("requestedThreads", task.requestedThreads)
                        put("threads", task.effectiveThreads)
                        put("error", task.error ?: JSONObject.NULL)
                        put("outputUri", task.outputUri ?: JSONObject.NULL)
                    },
                )
            }
        }.toString()
    }

    /** A compact snapshot used by the persistent foreground notification. */
    data class ActiveDownloadSnapshot(
        val fileName: String,
        val state: String,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val activeCount: Int,
    )

    fun activeDownloadSnapshot(): ActiveDownloadSnapshot? = synchronized(lock) {
        val activeTasks = tasks.values.filter {
            it.state == STATE_RUNNING || it.state == STATE_QUEUED
        }
        val task = activeTasks.firstOrNull { it.state == STATE_RUNNING }
            ?: activeTasks.firstOrNull()
            ?: return@synchronized null

        ActiveDownloadSnapshot(
            fileName = task.fileName,
            state = task.state,
            totalBytes = task.totalBytes,
            downloadedBytes = task.downloadedBytes,
            activeCount = activeTasks.size,
        )
    }

    /** Registers a listener for task lifecycle changes that should refresh status UI. */
    fun addStatusListener(listener: () -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: () -> Unit) {
        statusListeners.remove(listener)
    }

    fun start(
        url: String,
        requestedThreads: Int,
        suggestedFileName: String? = null,
        contentDisposition: String? = null,
        contentLocation: String? = null,
        contentType: String? = null,
        userAgent: String? = null,
        referrerUrl: String? = null,
    ): String {
        val normalizedUrl = url.trim()
        require(normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            "Only HTTP and HTTPS URLs can be downloaded"
        }

        val initialFileName = resolveSuggestedFileName(
            url = normalizedUrl,
            suggestedFileName = suggestedFileName,
            contentDisposition = contentDisposition,
            contentLocation = contentLocation,
            contentType = contentType,
        )

        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = normalizedUrl,
            fileName = initialFileName,
            requestedThreads = normalizeThreadCount(requestedThreads),
            autoResume = true,
            userAgent = userAgent?.takeIf { it.isNotBlank() },
            referrerUrl = referrerUrl?.takeIf { it.isNotBlank() },
        )
        synchronized(lock) {
            tasks[task.id] = task
            persistLocked()
        }
        launchTask(task.id)
        notifyStatusChanged()
        return task.id
    }

    fun pause(id: String) {
        synchronized(lock) {
            tasks[id]?.let { task ->
                if (task.state == STATE_RUNNING || task.state == STATE_QUEUED) {
                    task.state = STATE_PAUSED
                    task.autoResume = false
                    task.error = null
                    persistLocked()
                }
            }
        }
        disconnectAll(id)
        jobs.remove(id)?.cancel()
        notifyStatusChanged()
    }

    fun resume(id: String) {
        synchronized(lock) {
            tasks[id]?.let { task ->
                if (task.state == STATE_PAUSED || task.state == STATE_ERROR) {
                    task.state = STATE_QUEUED
                    task.autoResume = true
                    task.error = null
                    persistLocked()
                }
            }
        }
        launchTask(id)
        notifyStatusChanged()
    }

    fun restart(id: String) {
        pause(id)
        synchronized(lock) {
            tasks[id]?.let { task ->
                partFile(task).delete()
                task.totalBytes = 0L
                task.downloadedBytes = 0L
                task.effectiveThreads = 1
                task.segments.clear()
                task.state = STATE_QUEUED
                task.autoResume = true
                task.error = null
                task.outputUri = null
                persistLocked()
            }
        }
        launchTask(id)
        notifyStatusChanged()
    }

    fun delete(id: String) {
        val task = synchronized(lock) {
            tasks[id]?.let {
                deleteRequestedIds.add(id)
                tasks.remove(id)?.also { persistLocked() }
            }
        } ?: return
        disconnectAll(id)
        val job = jobs.remove(id)
        if (job == null) {
            partFile(task).delete()
            deleteRequestedIds.remove(id)
        } else {
            job.invokeOnCompletion {
                partFile(task).delete()
                deleteRequestedIds.remove(id)
            }
            job.cancel()
        }
        deletePublishedOutput(task.outputUri)
        notifyStatusChanged()
    }

    /**
     * Renames a task and, when it is already published, renames the file in
     * Downloads as well. If the user omits the old extension, preserve it so
     * Android and the inline preview continue to recognize the file type.
     */
    fun rename(id: String, requestedName: String): String {
        val snapshot = synchronized(lock) {
            tasks[id]?.let { task ->
                Triple(task.fileName, task.state, task.outputUri)
            }
        } ?: throw IOException("Download not found")

        val newName = renamedFileName(snapshot.first, requestedName)
        val renamedOutput = if (snapshot.second == STATE_COMPLETED && !snapshot.third.isNullOrBlank()) {
            renamePublishedFile(snapshot.third!!, newName)
        } else {
            null
        }

        synchronized(lock) {
            val task = tasks[id] ?: throw IOException("Download no longer exists")
            task.fileName = newName
            task.fileNameUserSet = true
            if (renamedOutput != null) task.outputUri = renamedOutput
            persistLocked()
        }
        return newName
    }

    fun urlFor(id: String): String? = synchronized(lock) { tasks[id]?.url }

    /** Returns true when the Downloads tab has at least one persisted task. */
    fun hasTasks(): Boolean = synchronized(lock) { tasks.isNotEmpty() }

    /** Resumes tasks that were interrupted while the process was being stopped. */
    fun resumeAutomatically() {
        val ids = synchronized(lock) {
            val resumable = tasks.values
                .filter { it.state == STATE_PAUSED && it.autoResume }
                .map { it.id }
            if (resumable.isNotEmpty()) {
                resumable.forEach { id ->
                    tasks[id]?.apply {
                        state = STATE_QUEUED
                        error = null
                    }
                }
                persistLocked()
            }
            resumable
        }
        ids.forEach(::launchTask)
        if (ids.isNotEmpty()) notifyStatusChanged()
    }

    /** The thread count selected in the Downloads tab, used for new browser downloads too. */
    fun preferredThreadCount(): Int = normalizeThreadCount(
        preferences.getInt(KEY_PREFERRED_THREADS, DEFAULT_PREFERRED_THREADS),
    )

    fun setPreferredThreadCount(value: Int): Int {
        val normalized = normalizeThreadCount(value)
        preferences.edit().putInt(KEY_PREFERRED_THREADS, normalized).apply()
        return normalized
    }

    /**
     * Chooses a useful name before the response probe completes. Generic URL
     * names such as content.xx are deliberately discarded so they can never
     * become the user-visible download name.
     */
    fun resolveSuggestedFileName(
        url: String,
        suggestedFileName: String?,
        contentDisposition: String?,
        contentLocation: String? = null,
        contentType: String? = null,
    ): String {
        val candidates = listOf(
            contentDispositionFileName(contentDisposition),
            candidateFileNameFor(contentLocation),
            sanitizeFileName(suggestedFileName.orEmpty()),
            candidateFileNameFor(url),
        )
        val candidate = candidates.firstOrNull { !it.isNullOrBlank() && !isGenericFileName(it) }
        return candidate
            ?.let { completeFileName(it, candidates, contentType) }
            ?.take(MAX_FILE_NAME_LENGTH)
            ?: fallbackFileName(contentType)
    }

    /** Metadata for a completed file that can be previewed or handed to Android. */
    data class DownloadedFileInfo(
        val id: String,
        val fileName: String,
        val mimeType: String,
        val uri: Uri,
        val length: Long,
    )

    fun fileInfoFor(id: String): DownloadedFileInfo? {
        val snapshot = synchronized(lock) {
            tasks[id]?.takeIf { it.state == STATE_COMPLETED && !it.outputUri.isNullOrBlank() }?.let {
                Triple(it.id, it.fileName, it.outputUri!!)
            }
        } ?: return null
        val uri = Uri.parse(snapshot.third)
        return DownloadedFileInfo(
            id = snapshot.first,
            fileName = snapshot.second,
            mimeType = URLConnectionGuess.mimeType(snapshot.second),
            uri = uri,
            length = fileLength(uri),
        )
    }

    fun openDownloadedInputStream(id: String): InputStream? =
        fileInfoFor(id)?.let { openInputStream(it.uri) }

    fun textPreview(id: String, maxBytes: Int = TEXT_PREVIEW_MAX_BYTES): String? {
        val info = fileInfoFor(id) ?: return null
        if (!isTextMimeType(info.mimeType)) return null
        val input = openInputStream(info.uri) ?: return null
        return input.use { stream ->
            // Read a bounded window from each end. The visible preview is
            // character-bounded, while the larger byte window avoids cutting
            // off UTF-8 characters before the 2,000-character limit.
            val windowBytes = TEXT_PREVIEW_WINDOW_BYTES.coerceAtMost(maxBytes.coerceAtLeast(1))
            val first = readAtMost(stream, windowBytes)
            if (info.length > first.size.toLong() && info.length <= windowBytes * 2L) {
                val rest = readAtMost(stream, (info.length - first.size).toInt())
                val whole = ByteArray(first.size + rest.size)
                first.copyInto(whole)
                rest.copyInto(whole, first.size)
                trimTextPreview(whole.toString(StandardCharsets.UTF_8))
            } else {
                val last = if (info.length > first.size.toLong()) {
                    val skip = (info.length - first.size - windowBytes).coerceAtLeast(0L)
                    if (skipFully(stream, skip)) readAtMost(stream, windowBytes) else null
                } else if (info.length < 0L && first.size == windowBytes) {
                    readRemainingTail(stream, windowBytes)
                } else {
                    null
                }
                if (last != null && last.isNotEmpty()) {
                    val firstText = first.toString(StandardCharsets.UTF_8)
                    val lastText = last.toString(StandardCharsets.UTF_8)
                    firstText.take(TEXT_PREVIEW_EDGE_CHARS) + PREVIEW_MIDDLE_MARKER +
                        lastText.takeLast(TEXT_PREVIEW_EDGE_CHARS)
                } else {
                    trimTextPreview(first.toString(StandardCharsets.UTF_8))
                }
            }
        }
    }

    /** Renders the first PDF page for an inline, lightweight preview in the Downloads tab. */
    fun pdfPreviewPng(id: String): ByteArray? {
        val info = fileInfoFor(id) ?: return null
        if (info.mimeType != "application/pdf") return null
        val descriptor = openParcelFileDescriptor(info.uri) ?: return null
        return try {
            val renderer = android.graphics.pdf.PdfRenderer(descriptor)
            try {
                if (renderer.pageCount == 0) return null
                val page = renderer.openPage(0)
                try {
                    val scale = min(1.5f, 1400f / maxOf(page.width, page.height).toFloat())
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        bitmap.recycle()
                        output.toByteArray()
                    }
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } finally {
            descriptor.close()
        }
    }

    private fun launchTask(id: String) {
        val job = synchronized(lock) {
            if (tasks[id] == null || jobs[id]?.isActive == true) return
            scope.launch {
                runTask(id)
            }.also { jobs[id] = it }
        }
        job.invokeOnCompletion { jobs.remove(id, job) }
    }

    private suspend fun runTask(id: String) {
        val initialTask = synchronized(lock) { tasks[id] } ?: return
        if (initialTask.state == STATE_COMPLETED) return

        updateTask(id) {
            it.state = STATE_RUNNING
            it.error = null
        }
        notifyStatusChanged()

        try {
            val probe = probe(initialTask, id)
            updateTask(id) { task ->
                task.fileName = resolveFileName(task, probe)
                task.totalBytes = probe.totalBytes
                if (!probe.supportsRanges || probe.totalBytes <= 0L) {
                    task.effectiveThreads = 1
                    task.segments = mutableListOf(Segment(0L, -1L, 0L))
                } else {
                    ensureSegments(task, probe.totalBytes)
                }
            }

            if (probe.supportsRanges && probe.totalBytes > 0L) {
                try {
                    val count = synchronized(lock) { tasks[id]?.segments?.size ?: 1 }
                    coroutineScope {
                        (0 until count).map { index ->
                            async(Dispatchers.IO) { downloadSegment(id, index) }
                        }.awaitAll()
                    }
                } catch (_: RangeUnsupportedException) {
                    // Some servers advertise a one-byte range probe but do not
                    // honor the later segment requests. Restart safely as one
                    // stream instead of leaving the task failed or corrupted.
                    switchToSingleThread(id)
                    downloadWithoutRanges(id)
                }
            } else {
                downloadWithoutRanges(id)
            }

            coroutineContext.ensureActive()
            val completedTask = synchronized(lock) {
                tasks[id]?.takeUnless { deleteRequestedIds.contains(id) }
            } ?: return
            val output = publish(completedTask)
            val shouldDeletePublishedOutput = synchronized(lock) {
                val task = tasks[id]
                if (task == null || deleteRequestedIds.contains(id)) {
                    true
                } else {
                    task.state = STATE_COMPLETED
                    task.autoResume = false
                    task.downloadedBytes = if (task.totalBytes > 0L) task.totalBytes else task.downloadedBytes
                    task.outputUri = output
                    task.error = null
                    persistLocked()
                    false
                }
            }
            if (shouldDeletePublishedOutput) {
                deletePublishedOutput(output)
                return
            }
            notifyStatusChanged()
        } catch (cancelled: CancellationException) {
            // pause() changes the state before cancelling the worker. Preserve
            // that state and the segment offsets for a later resume.
            if (stateOf(id) != STATE_PAUSED) throw cancelled
        } catch (error: Throwable) {
            if (stateOf(id) != STATE_PAUSED) {
                updateTask(id) { task ->
                    task.state = STATE_ERROR
                    task.autoResume = false
                    task.error = error.message?.take(240) ?: error.javaClass.simpleName
                }
                notifyStatusChanged()
            }
        } finally {
            disconnectAll(id)
        }
    }

    private fun ensureSegments(task: DownloadTask, totalBytes: Long) {
        val wanted = normalizeThreadCount(task.requestedThreads)
        val valid = task.segments.size == wanted && task.segments.allIndexed { index, segment ->
            val expectedStart = totalBytes * index / wanted
            val expectedEnd = totalBytes * (index + 1) / wanted - 1L
            segment.start == expectedStart && segment.end == expectedEnd &&
                segment.downloaded in 0L..(expectedEnd - expectedStart + 1L)
        }
        if (!valid) {
            task.segments = MutableList(wanted) { index ->
                Segment(
                    start = totalBytes * index / wanted,
                    end = totalBytes * (index + 1) / wanted - 1L,
                    downloaded = 0L,
                )
            }
        }
        task.effectiveThreads = wanted
        task.downloadedBytes = task.segments.sumOf { it.downloaded }
    }

    private fun switchToSingleThread(id: String) {
        val task = synchronized(lock) { tasks[id] } ?: return
        disconnectAll(id)
        partFile(task).delete()
        updateTask(id) {
            it.effectiveThreads = 1
            it.totalBytes = it.totalBytes.coerceAtLeast(-1L)
            it.downloadedBytes = 0L
            it.segments = mutableListOf(Segment(0L, -1L, 0L))
        }
    }

    private suspend fun downloadSegment(id: String, index: Int) {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val snapshot = synchronized(lock) {
                tasks[id]?.segments?.getOrNull(index)?.copy()
            } ?: return
            val nextByte = snapshot.start + snapshot.downloaded
            if (nextByte > snapshot.end) return

            try {
                val task = synchronized(lock) { tasks[id] } ?: return
                if (httpClient != null) {
                    val response = openClientResponse(
                        id = id,
                        url = task.url,
                        headers = mapOf("Range" to "bytes=$nextByte-${snapshot.end}"),
                        userAgent = task.userAgent,
                        referrerUrl = task.referrerUrl,
                    )
                    try {
                        val responseLength = validateRangeResponse(
                            status = response.status,
                            contentRange = headerValue(response.headers, "Content-Range"),
                            requestedStart = nextByte,
                            requestedEnd = snapshot.end,
                        )
                        response.body.useStream { input ->
                            copySegmentResponse(id, index, nextByte, responseLength, input)
                        }
                    } finally {
                        unregisterResponse(id, response)
                        response.close()
                    }
                } else {
                    val connection = openConnection(id, task.url, task.userAgent, task.referrerUrl)
                    try {
                        connection.setRequestProperty("Range", "bytes=$nextByte-${snapshot.end}")
                        connection.connect()
                        val code = connection.responseCode
                        val responseLength = validateRangeResponse(
                            status = code,
                            contentRange = connection.getHeaderField("Content-Range"),
                            requestedStart = nextByte,
                            requestedEnd = snapshot.end,
                        )
                        connection.inputStream.use { input ->
                            copySegmentResponse(id, index, nextByte, responseLength, input)
                        }
                    } finally {
                        unregisterConnection(id, connection)
                        connection.disconnect()
                    }
                }

                val finished = synchronized(lock) {
                    tasks[id]?.segments?.getOrNull(index)?.let { it.start + it.downloaded > it.end } == true
                }
                if (finished) return
                if (attempt++ >= MAX_RETRIES) throw IOException("Connection ended before the segment completed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unsupported: RangeUnsupportedException) {
                throw unsupported
            } catch (error: Throwable) {
                if (attempt++ >= MAX_RETRIES) throw error
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    /**
     * Accept only a response whose Content-Range starts at the byte requested
     * by this segment and does not extend past that segment. Some servers
     * return HTTP 206 while ignoring the requested range; accepting those
     * bytes would corrupt the assembled file, so the caller falls back to one
     * sequential connection instead.
     */
    private fun validateRangeResponse(
        status: Int,
        contentRange: String?,
        requestedStart: Long,
        requestedEnd: Long,
    ): Long {
        if (status != HttpURLConnection.HTTP_PARTIAL) {
            throw RangeUnsupportedException(
                "Server returned HTTP $status for a ranged request",
            )
        }
        val match = RANGE_PATTERN.matchEntire(contentRange?.trim().orEmpty())
            ?: throw RangeUnsupportedException("Server did not return a usable Content-Range")
        val start = match.groupValues[1].toLongOrNull()
            ?: throw RangeUnsupportedException("Server returned an invalid range start")
        val end = match.groupValues[2].toLongOrNull()
            ?: throw RangeUnsupportedException("Server returned an invalid range end")
        if (start != requestedStart || end < start || end > requestedEnd) {
            throw RangeUnsupportedException(
                "Server returned bytes $start-$end for requested bytes $requestedStart-$requestedEnd",
            )
        }
        return end - start + 1L
    }

    private fun copySegmentResponse(
        id: String,
        index: Int,
        nextByte: Long,
        responseLength: Long,
        input: InputStream,
    ) {
        var remaining = responseLength
        RandomAccessFile(partFileForId(id), "rw").use { output ->
            output.seek(nextByte)
            val buffer = ByteArray(BUFFER_SIZE)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                updateSegmentProgress(id, index, read.toLong())
                remaining -= read
            }
        }
    }

    private suspend fun downloadWithoutRanges(id: String) {
        val task = synchronized(lock) { tasks[id] } ?: return
        val file = partFile(task)
        val existingBytes = file.length()
        if (task.totalBytes > 0L && existingBytes >= task.totalBytes) {
            updateTask(id) { it.downloadedBytes = task.totalBytes }
            return
        }

        if (httpClient != null) {
            val response = openClientResponse(
                id = id,
                url = task.url,
                userAgent = task.userAgent,
                referrerUrl = task.referrerUrl,
            )
            try {
                if (response.status !in 200..299) throw IOException("Server returned HTTP ${response.status}")
                response.body.useStream { input ->
                    copyResumableStream(id, file, existingBytes, input)
                }
            } finally {
                unregisterResponse(id, response)
                response.close()
            }
        } else {
            val connection = openConnection(id, task.url, task.userAgent, task.referrerUrl)
            try {
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("Server returned HTTP $code")
                connection.inputStream.use { input ->
                    copyResumableStream(id, file, existingBytes, input)
                }
            } finally {
                unregisterConnection(id, connection)
                connection.disconnect()
            }
        }
    }

    private fun copyResumableStream(
        id: String,
        file: File,
        existingBytes: Long,
        input: java.io.InputStream,
    ) {
        var remaining = existingBytes
        val skipBuffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0L) {
            val read = input.read(skipBuffer, 0, min(remaining, skipBuffer.size.toLong()).toInt())
            if (read <= 0) throw IOException("Connection ended while resuming")
            remaining -= read
        }
        RandomAccessFile(file, "rw").use { output ->
            output.seek(existingBytes)
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                updateTaskProgress(id, read.toLong())
            }
        }
    }

    private fun probe(task: DownloadTask, id: String): ProbeResult {
        if (httpClient != null) {
            val response = openClientResponse(
                id = id,
                url = task.url,
                headers = mapOf("Range" to "bytes=0-0"),
                userAgent = task.userAgent,
                referrerUrl = task.referrerUrl,
            )
            return try {
                if (response.status !in 200..299) throw IOException("Server returned HTTP ${response.status}")
                val contentRange = headerValue(response.headers, "Content-Range")
                val totalFromRange = contentRange
                    ?.substringAfter('/', "")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                val contentLength = headerValue(response.headers, "Content-Length")?.toLongOrNull()
                val total = totalFromRange ?: contentLength?.takeIf { it > 0L } ?: -1L
                val supportsRanges = response.status == HttpURLConnection.HTTP_PARTIAL && totalFromRange != null
                response.body.useStream { it.read() }
                ProbeResult(
                    totalBytes = total,
                    supportsRanges = supportsRanges,
                    dispositionFileName = responseFileName(response.headers),
                    contentLocationFileName = candidateFileNameFor(
                        headerValue(response.headers, "Content-Location"),
                    ),
                    urlFileName = candidateFileNameFor(response.url),
                    contentType = headerValue(response.headers, "Content-Type"),
                )
            } finally {
                unregisterResponse(id, response)
                response.close()
            }
        }

        val connection = openConnection(id, task.url, task.userAgent, task.referrerUrl)
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Server returned HTTP $code")
            val contentRange = connection.getHeaderField("Content-Range")
            val totalFromRange = contentRange
                ?.substringAfter('/', "")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            val total = totalFromRange ?: connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            val supportsRanges = code == HttpURLConnection.HTTP_PARTIAL && totalFromRange != null
            connection.inputStream.use { it.read() }
            ProbeResult(
                totalBytes = total,
                supportsRanges = supportsRanges,
                dispositionFileName = connectionFileName(connection),
                contentLocationFileName = candidateFileNameFor(
                    connection.getHeaderField("Content-Location"),
                ),
                urlFileName = candidateFileNameFor(connection.url.toString()),
                contentType = connection.getHeaderField("Content-Type"),
            )
        } finally {
            unregisterConnection(id, connection)
            connection.disconnect()
        }
    }

    private fun publish(task: DownloadTask): String {
        val source = partFile(task)
        if (!source.exists()) throw IOException("Download data is missing")
        val mimeType = URLConnectionGuess.mimeType(task.fileName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, task.fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw IOException("Could not create the Downloads file")
            try {
                appContext.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not open the Downloads file")
                appContext.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
                return uri.toString()
            } catch (error: Throwable) {
                appContext.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val publicDirectory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, Environment.DIRECTORY_DOWNLOADS)
        publicDirectory.mkdirs()
        val target = uniqueFile(publicDirectory, task.fileName)
        source.copyTo(target, overwrite = true)
        return target.toURI().toString()
    }

    private fun updateSegmentProgress(id: String, index: Int, delta: Long) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            val segment = task.segments.getOrNull(index) ?: return
            segment.downloaded = (segment.downloaded + delta).coerceAtMost(segment.end - segment.start + 1L)
            task.downloadedBytes = task.segments.sumOf { it.downloaded }
            persistIfDueLocked(id)
        }
    }

    private fun updateTaskProgress(id: String, delta: Long) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            task.downloadedBytes += delta
            if (task.segments.isNotEmpty()) task.segments[0].downloaded = task.downloadedBytes
            persistIfDueLocked(id)
        }
    }

    private fun updateTask(id: String, update: (DownloadTask) -> Unit) {
        synchronized(lock) {
            tasks[id]?.let {
                update(it)
                persistLocked()
            }
        }
    }

    private fun deletePublishedOutput(output: String?) {
        if (output.isNullOrBlank()) return
        runCatching {
            val uri = Uri.parse(output)
            if (uri.scheme.equals("content", ignoreCase = true)) {
                appContext.contentResolver.delete(uri, null, null)
            } else {
                File(uri.path ?: output).delete()
            }
        }
    }

    private fun notifyStatusChanged() {
        statusListeners.forEach { listener ->
            runCatching { listener() }
        }
    }

    private fun stateOf(id: String): String? = synchronized(lock) { tasks[id]?.state }

    private fun openConnection(
        id: String,
        url: String,
        userAgent: String? = null,
        referrerUrl: String? = null,
    ): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", userAgent ?: USER_AGENT)
            referrerUrl?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
        }
        connections.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }.add(connection)
        return connection
    }

    private fun openClientResponse(
        id: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
        referrerUrl: String? = null,
    ): Response {
        val requestHeaders = MutableHeaders().apply {
            set("Accept-Encoding", "identity")
            set("User-Agent", userAgent ?: USER_AGENT)
            referrerUrl?.takeIf { it.isNotBlank() }?.let { set("Referer", it) }
            headers.forEach { (name, value) -> set(name, value) }
        }
        val request = Request(
            url = url,
            method = Request.Method.GET,
            headers = requestHeaders,
            connectTimeout = CONNECT_TIMEOUT_MS.toLong() to TimeUnit.MILLISECONDS,
            readTimeout = READ_TIMEOUT_MS.toLong() to TimeUnit.MILLISECONDS,
            redirect = Request.Redirect.FOLLOW,
            cookiePolicy = Request.CookiePolicy.INCLUDE,
            useCaches = false,
        )
        val response = httpClient?.fetch(request) ?: throw IOException("Download client unavailable")
        responses.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }.add(response)
        return response
    }

    private fun unregisterConnection(id: String, connection: HttpURLConnection) {
        connections[id]?.remove(connection)
    }

    private fun unregisterResponse(id: String, response: Response) {
        responses[id]?.remove(response)
    }

    private fun disconnectAll(id: String) {
        connections.remove(id)?.forEach { connection -> runCatching { connection.disconnect() } }
        responses.remove(id)?.forEach { response -> runCatching { response.close() } }
    }

    private fun partFile(task: DownloadTask): File = File(storageDirectory, "${task.id}.part")

    private fun partFileForId(id: String): File = File(storageDirectory, "$id.part")

    private fun openInputStream(uri: Uri): InputStream? = when (uri.scheme?.lowercase()) {
        "content" -> appContext.contentResolver.openInputStream(uri)
        "file" -> uri.path?.let { path -> File(path).inputStream() }
        else -> null
    }

    private fun openParcelFileDescriptor(uri: Uri): ParcelFileDescriptor? = when (uri.scheme?.lowercase()) {
        "content" -> appContext.contentResolver.openFileDescriptor(uri, "r")
        "file" -> uri.path?.let { path ->
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        else -> null
    }

    private fun fileLength(uri: Uri): Long = when (uri.scheme?.lowercase()) {
        "content" -> runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L } ?: descriptor.parcelFileDescriptor.statSize
            } ?: -1L
        }.getOrDefault(-1L)
        "file" -> uri.path?.let { File(it).length() } ?: -1L
        else -> -1L
    }

    private fun isTextMimeType(mimeType: String): Boolean =
        mimeType.startsWith("text/") || mimeType in setOf(
            "application/json",
            "application/javascript",
            "application/xml",
            "application/xhtml+xml",
            "application/rtf",
        )

    private fun loadTasks() {
        val array = runCatching { JSONArray(preferences.getString(KEY_TASKS, "[]")) }.getOrDefault(JSONArray())
        synchronized(lock) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val url = item.optString("url")
                if (id.isBlank() || url.isBlank()) continue
                val segments = mutableListOf<Segment>()
                val segmentArray = item.optJSONArray("segments") ?: JSONArray()
                for (segmentIndex in 0 until segmentArray.length()) {
                    val segment = segmentArray.optJSONObject(segmentIndex) ?: continue
                    segments += Segment(
                        start = segment.optLong("start"),
                        end = segment.optLong("end", -1L),
                        downloaded = segment.optLong("downloaded").coerceAtLeast(0L),
                    )
                }
                val persistedState = item.optString("state", STATE_PAUSED)
                val state = persistedState.let {
                    if (it == STATE_RUNNING || it == STATE_QUEUED) STATE_PAUSED else it
                }
                tasks[id] = DownloadTask(
                    id = id,
                    url = url,
                    fileName = item.optString("fileName").ifBlank { fileNameFor(url) },
                    fileNameUserSet = item.optBoolean("fileNameUserSet", false),
                    autoResume = item.optBoolean(
                        "autoResume",
                        persistedState == STATE_RUNNING || persistedState == STATE_QUEUED,
                    ),
                    requestedThreads = normalizeThreadCount(item.optInt("requestedThreads", 1)),
                    effectiveThreads = item.optInt("threads", 1).coerceAtLeast(1),
                    totalBytes = item.optLong("totalBytes", -1L),
                    downloadedBytes = item.optLong("downloadedBytes").coerceAtLeast(0L),
                    state = state,
                    error = item.optString("error").takeIf { it.isNotBlank() },
                    outputUri = item.optString("outputUri").takeIf { it.isNotBlank() },
                    userAgent = item.optString("userAgent").takeIf { it.isNotBlank() },
                    referrerUrl = item.optString("referrerUrl").takeIf { it.isNotBlank() },
                    segments = segments,
                )
            }
        }
    }

    private fun persistIfDueLocked(id: String) {
        val now = System.currentTimeMillis()
        if (now - (lastPersistAt[id] ?: 0L) >= PERSIST_INTERVAL_MS) {
            lastPersistAt[id] = now
            persistLocked()
        }
    }

    private fun persistLocked() {
        val array = JSONArray()
        tasks.values.forEach { task ->
            array.put(
                JSONObject().apply {
                    put("id", task.id)
                    put("url", task.url)
                    put("fileName", task.fileName)
                    put("fileNameUserSet", task.fileNameUserSet)
                    put("autoResume", task.autoResume)
                    put("requestedThreads", task.requestedThreads)
                    put("threads", task.effectiveThreads)
                    put("totalBytes", task.totalBytes)
                    put("downloadedBytes", task.downloadedBytes)
                    put("state", task.state)
                    task.error?.let { put("error", it) }
                    task.outputUri?.let { put("outputUri", it) }
                    task.userAgent?.let { put("userAgent", it) }
                    task.referrerUrl?.let { put("referrerUrl", it) }
                    put("segments", JSONArray().apply {
                        task.segments.forEach { segment ->
                            put(
                                JSONObject().apply {
                                    put("start", segment.start)
                                    put("end", segment.end)
                                    put("downloaded", segment.downloaded)
                                },
                            )
                        }
                    })
                },
            )
        }
        preferences.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    private fun fileNameFor(url: String): String {
        return candidateFileNameFor(url)
            ?.takeUnless(::isGenericFileName)
            ?.take(MAX_FILE_NAME_LENGTH)
            ?: fallbackFileName()
    }

    private fun resolveFileName(task: DownloadTask, probe: ProbeResult): String {
        val candidates = if (task.fileNameUserSet) {
            listOf(
                task.fileName,
                probe.dispositionFileName,
                probe.contentLocationFileName,
                probe.urlFileName,
            )
        } else {
            listOf(
                probe.dispositionFileName,
                probe.contentLocationFileName,
                task.fileName,
                probe.urlFileName,
            )
        }
        val candidate = candidates.firstOrNull { !it.isNullOrBlank() && !isGenericFileName(it) }
        return candidate
            ?.let { completeFileName(it, candidates, probe.contentType) }
            ?.take(MAX_FILE_NAME_LENGTH)
            ?: fallbackFileName(probe.contentType)
    }

    private fun completeFileName(
        candidate: String,
        candidates: List<String?>,
        contentType: String?,
    ): String {
        val clean = sanitizeFileName(candidate) ?: return candidate
        if (hasFileExtension(clean)) return clean

        val extension = candidates.asSequence()
            .mapNotNull(::fileExtension)
            .firstOrNull()
            ?: contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?.takeUnless { it.isNullOrBlank() }

        return extension?.let { "$clean.$it" } ?: clean
    }

    private fun fileExtension(value: String?): String? {
        val clean = sanitizeFileName(value.orEmpty()) ?: return null
        val extension = clean.substringAfterLast('.', "")
        return extension
            .takeIf { clean.lastIndexOf('.') > 0 && it.length in 1..12 }
            ?.takeIf { it.all { character -> character.isLetterOrDigit() } }
    }

    private fun hasFileExtension(value: String): Boolean = fileExtension(value) != null

    private fun renamedFileName(originalName: String, requestedName: String): String {
        val clean = sanitizeFileName(requestedName)
            ?.takeUnless(::isGenericFileName)
            ?: throw IOException("Enter a valid file name")
        if (hasFileExtension(clean)) return clean.take(MAX_FILE_NAME_LENGTH)

        val extension = fileExtension(originalName)
        return (extension?.let { "$clean.$it" } ?: clean).take(MAX_FILE_NAME_LENGTH)
    }

    private fun renamePublishedFile(output: String, newName: String): String {
        val uri = Uri.parse(output)
        return when (uri.scheme?.lowercase()) {
            "content" -> {
                val updated = appContext.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                    },
                    null,
                    null,
                )
                if (updated <= 0) throw IOException("Could not rename the downloaded file")
                output
            }

            "file" -> {
                val source = File(uri.path ?: throw IOException("Downloaded file path is missing"))
                val parent = source.parentFile
                    ?: throw IOException("Downloaded file directory is missing")
                val target = File(parent, newName)
                if (source.absolutePath != target.absolutePath && target.exists()) {
                    throw IOException("A file with that name already exists")
                }
                if (source.absolutePath != target.absolutePath && !source.renameTo(target)) {
                    throw IOException("Could not rename the downloaded file")
                }
                target.toURI().toString()
            }

            else -> throw IOException("This downloaded file cannot be renamed")
        }
    }

    private fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(BUFFER_SIZE)
        val output = ByteArrayOutputStream(maxBytes)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = input.read(buffer, 0, min(buffer.size, remaining))
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun skipFully(input: InputStream, bytes: Long): Boolean {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) return false
            remaining--
        }
        return true
    }

    private fun readRemainingTail(input: InputStream, maxBytes: Int): ByteArray {
        val ring = ByteArray(maxBytes)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            for (index in 0 until read) {
                ring[((total + index) % maxBytes).toInt()] = buffer[index]
            }
            total += read
        }
        val count = min(total, maxBytes.toLong()).toInt()
        val result = ByteArray(count)
        val start = ((total - count) % maxBytes).toInt()
        for (index in 0 until count) {
            result[index] = ring[(start + index) % maxBytes]
        }
        return result
    }

    private fun trimTextPreview(text: String): String =
        if (text.length <= TEXT_PREVIEW_TOTAL_CHARS) {
            text
        } else {
            text.take(TEXT_PREVIEW_EDGE_CHARS) + PREVIEW_MIDDLE_MARKER +
                text.takeLast(TEXT_PREVIEW_EDGE_CHARS)
        }

    /**
     * Downloads commonly use a generic URL such as /content.xx and put the
     * user-facing name in Content-Disposition. Prefer filename* because it
     * preserves UTF-8 names, then fall back to the older filename parameter.
     */
    private fun contentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null

        val encoded = Regex(
            "filename\\*\\s*=\\s*([^;]+)",
            RegexOption.IGNORE_CASE,
        ).find(header)?.groupValues?.getOrNull(1)
        val plain = Regex(
            "filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))",
            RegexOption.IGNORE_CASE,
        ).find(header)?.let { match -> match.groupValues[1].ifBlank { match.groupValues[2] } }

        val candidate = encoded ?: plain ?: return null
        return decodeFileNameValue(candidate)
    }

    private fun responseFileName(headers: Headers): String? {
        contentDispositionFileName(headerValue(headers, "Content-Disposition"))?.let { return it }
        return listOf("X-Filename", "X-File-Name", "X-Download-Name", "X-Download-Filename")
            .asSequence()
            .mapNotNull { headerValue(headers, it) }
            .mapNotNull(::decodeFileNameValue)
            .firstOrNull { !isGenericFileName(it) }
    }

    private fun connectionFileName(connection: HttpURLConnection): String? {
        contentDispositionFileName(connection.getHeaderField("Content-Disposition"))?.let { return it }
        return listOf("X-Filename", "X-File-Name", "X-Download-Name", "X-Download-Filename")
            .asSequence()
            .mapNotNull { connection.getHeaderField(it) }
            .mapNotNull(::decodeFileNameValue)
            .firstOrNull { !isGenericFileName(it) }
    }

    private fun headerValue(headers: Headers, vararg names: String): String? {
        names.asSequence().forEach { name ->
            headers.getAll(name).firstOrNull { it.isNotBlank() }?.let { return it }
            headers.get(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun decodeFileNameValue(value: String): String? {
        val trimmed = value.trim().trim('"')
        val withoutCharset = trimmed.substringAfter("''", trimmed)
        val decoded = runCatching {
            URLDecoder.decode(
                withoutCharset.replace("+", "%2B"),
                StandardCharsets.UTF_8.name(),
            )
        }.getOrDefault(withoutCharset)
        return sanitizeFileName(decoded)
    }

    private fun candidateFileNameFor(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val queryName = listOf(
            "filename",
            "file_name",
            "download_filename",
            "download_name",
        ).asSequence()
            .mapNotNull { key -> runCatching { uri.getQueryParameter(key) }.getOrNull() }
            .mapNotNull(::sanitizeFileName)
            .firstOrNull { !isGenericFileName(it) }
        return queryName ?: uri.lastPathSegment
            ?.let(::decodeFileNameValue)
    }

    private fun fallbackFileName(contentType: String? = null): String {
        val extension = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeUnless { it.isNullOrBlank() }
        return "download-${System.currentTimeMillis()}${extension?.let { ".${it}" }.orEmpty()}"
    }

    private fun isGenericFileName(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.matches(Regex("content(?:\\.[a-z0-9]+)?")) ||
            normalized.matches(Regex("download(?:[-_.].*)?")) ||
            normalized in setOf("file", "blob", "unknown", "untitled")
    }

    private fun sanitizeFileName(value: String): String? {
        val leaf = value
            .trim()
            .trim('"')
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("[\\p{Cntrl}\\/:*?\"<>|]"), "_")
            .trim()
        return leaf.takeUnless { it.isBlank() || it == "." || it == ".." }
    }

    private fun uniqueFile(directory: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").takeIf { it != name }
        var index = 0
        var candidate = File(directory, name)
        while (candidate.exists()) {
            index += 1
            candidate = File(directory, "$base-$index${extension?.let { ".${it}" }.orEmpty()}")
        }
        return candidate
    }

    private fun normalizeThreadCount(value: Int): Int = when (value) {
        1, 4, 8, 16 -> value
        else -> 1
    }

    private class RangeUnsupportedException(message: String) : IOException(message)

    private data class ProbeResult(
        val totalBytes: Long,
        val supportsRanges: Boolean,
        val dispositionFileName: String?,
        val contentLocationFileName: String?,
        val urlFileName: String?,
        val contentType: String?,
    )

    private data class Segment(
        val start: Long,
        val end: Long,
        var downloaded: Long,
    ) {
        fun copy(): Segment = Segment(start, end, downloaded)
    }

    private data class DownloadTask(
        val id: String,
        val url: String,
        var fileName: String,
        val requestedThreads: Int,
        var fileNameUserSet: Boolean = false,
        var autoResume: Boolean = false,
        var effectiveThreads: Int = 1,
        var totalBytes: Long = -1L,
        var downloadedBytes: Long = 0L,
        var state: String = STATE_QUEUED,
        var error: String? = null,
        var outputUri: String? = null,
        var userAgent: String? = null,
        var referrerUrl: String? = null,
        var segments: MutableList<Segment> = mutableListOf(),
    )

    private object URLConnectionGuess {
        fun mimeType(fileName: String): String {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (extension) {
                "html", "htm" -> "text/html"
                "json" -> "application/json"
                "txt", "log", "md", "markdown", "csv" -> "text/plain"
                "xml" -> "application/xml"
                "js" -> "application/javascript"
                "css" -> "text/css"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "svg" -> "image/svg+xml"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "apk" -> "application/vnd.android.package-archive"
                "aab" -> "application/octet-stream"
                "mp4" -> "video/mp4"
                "m4v" -> "video/x-m4v"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "ogg", "oga" -> "audio/ogg"
                "flac" -> "audio/flac"
                else -> "application/octet-stream"
            }
        }
    }

    companion object {
        const val STATE_QUEUED = "QUEUED"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_ERROR = "ERROR"

        private const val PREFERENCES = "firefoxlite_download_manager"
        private const val KEY_TASKS = "tasks"
        private const val KEY_PREFERRED_THREADS = "preferred_threads"
        private const val STORAGE_DIRECTORY = "firefoxlite-downloads"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 750L
        private const val PERSIST_INTERVAL_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT = "FirefoxLite Download Manager/1.0"
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val TEXT_PREVIEW_MAX_BYTES = 512 * 1024
        private const val TEXT_PREVIEW_WINDOW_BYTES = 16 * 1024
        private const val TEXT_PREVIEW_EDGE_CHARS = 2_000
        private const val TEXT_PREVIEW_TOTAL_CHARS = TEXT_PREVIEW_EDGE_CHARS * 2
        private const val PREVIEW_MIDDLE_MARKER = "\n\n… [middle omitted] …\n\n"
        private const val DEFAULT_PREFERRED_THREADS = 4
        private val RANGE_PATTERN = Regex(
            "bytes\\s+(\\d+)-(\\d+)/(?:\\d+|\\*)",
            RegexOption.IGNORE_CASE,
        )
    }
}

private fun <T> Iterable<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, value -> if (!predicate(index, value)) return false }
    return true
}
