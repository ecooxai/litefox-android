/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.downloads

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.reference.browser.NotificationManager
import org.mozilla.reference.browser.ext.components

/**
 * Keeps Firefox Lite's persisted download manager alive after the activity is
 * put in the background and publishes a quiet, ongoing status notification.
 */
class FirefoxLiteBackgroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusJob: Job? = null
    private var startedAtElapsedRealtime = 0L
    private val statusListener: () -> Unit = {
        serviceScope.launch { publishCurrentStatus() }
    }

    private val downloadManager: FirefoxLiteDownloadManager
        get() = applicationContext.components.firefoxLiteDownloadManager

    override fun onCreate() {
        super.onCreate()
        startedAtElapsedRealtime = SystemClock.elapsedRealtime()
        downloadManager.addStatusListener(statusListener)
        downloadManager.resumeAutomatically()

        val initialNotification = NotificationManager.buildBackgroundStatusNotification(
            context = this,
            download = downloadManager.activeDownloadSnapshot(),
            uptimeMillis = 0L,
        )
        startAsForeground(initialNotification)

        statusJob = serviceScope.launch {
            while (isActive) {
                val snapshot = downloadManager.activeDownloadSnapshot()
                publishCurrentStatus(snapshot)
                delay(if (snapshot == null) IDLE_UPDATE_INTERVAL_MS else ACTIVE_UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        downloadManager.resumeAutomatically()
        return START_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        downloadManager.removeStatusListener(statusListener)
        serviceScope.cancel()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publishCurrentStatus(
        snapshot: FirefoxLiteDownloadManager.ActiveDownloadSnapshot? =
            downloadManager.activeDownloadSnapshot(),
    ) {
        NotificationManager.postBackgroundStatus(
            context = this,
            download = snapshot,
            uptimeMillis = SystemClock.elapsedRealtime() - startedAtElapsedRealtime,
        )
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationManager.BACKGROUND_STATUS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NotificationManager.BACKGROUND_STATUS_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTIVE_UPDATE_INTERVAL_MS = 1_000L
        private const val IDLE_UPDATE_INTERVAL_MS = 30_000L

        fun start(context: android.content.Context) {
            val intent = Intent(context.applicationContext, FirefoxLiteBackgroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    @Suppress("DEPRECATION")
                    context.applicationContext.startService(intent)
                }
            }.onFailure {
                Logger("FirefoxLiteBackgroundService").error(
                    "Unable to start background service: ${it.message.orEmpty()}",
                )
            }
        }
    }
}
