/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.lib.crash.Crash
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.view.setupPersistentInsets
import mozilla.components.support.utils.SafeIntent
import mozilla.components.support.webextensions.WebExtensionPopupObserver
import org.mozilla.reference.browser.addons.WebExtensionActionPopupActivity
import org.mozilla.reference.browser.browser.BrowserFragment
import org.mozilla.reference.browser.browser.CrashIntegration
import org.mozilla.reference.browser.downloads.FirefoxLiteBackgroundService
import org.mozilla.reference.browser.ext.components

/**
 * Activity that holds the [BrowserFragment].
 */
open class BrowserActivity : AppCompatActivity() {
    private lateinit var crashIntegration: CrashIntegration

    private val sessionId: String?
        get() = SafeIntent(intent).getStringExtra(EXTRA_SESSION_ID)

    private val webExtensionPopupObserver by lazy {
        WebExtensionPopupObserver(components.core.store, ::openPopup)
    }

    /**
     * Returns a new instance of [BrowserFragment] to display.
     */
    open fun createBrowserFragment(sessionId: String?): Fragment = BrowserFragment.create(sessionId)

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT))
        window.setupPersistentInsets()

        components.notificationsDelegate.bindToActivity(this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().apply {
                replace(R.id.container, createBrowserFragment(sessionId))
                commit()
            }
        }

        crashIntegration = CrashIntegration(this, components.analytics.crashReporter) { crash ->
            onNonFatalCrash(crash)
        }
        lifecycle.addObserver(crashIntegration)

        NotificationManager.checkAndNotifyPolicy(this)
        FirefoxLiteBackgroundService.start(this)
        requestBackgroundNotificationPermission()
        lifecycle.addObserver(webExtensionPopupObserver)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    supportFragmentManager.fragments.asReversed().forEach {
                        if (it is UserInteractionHandler && it.onBackPressed()) {
                            return
                        }
                    }
                }
            },
        )
    }

    private fun requestBackgroundNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val preferences = getSharedPreferences(BACKGROUND_STATUS_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(BACKGROUND_STATUS_PERMISSION_REQUESTED, false)) return

        preferences.edit().putBoolean(BACKGROUND_STATUS_PERMISSION_REQUESTED, true).apply()
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            BACKGROUND_STATUS_PERMISSION_REQUEST_CODE,
        )
    }

    @Suppress("DEPRECATION") // ComponentActivity wants us to use registerForActivityResult
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Logger.info(
            "Activity onActivityResult received with " +
                "requestCode: $requestCode, resultCode: $resultCode, data: $data",
        )

        val normalizedData = normalizeFilePickerResult(requestCode, data)

        supportFragmentManager.fragments.forEach {
            if (it is ActivityResultHandler && it.onActivityResult(requestCode, normalizedData, resultCode)) {
                return
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        components.notificationsDelegate.unBindActivity(this)
    }

    override fun onUserLeaveHint() {
        supportFragmentManager.fragments.forEach {
            if (it is UserInteractionHandler && it.onHomePressed()) {
                return
            }
        }

        super.onUserLeaveHint()
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? =
        when (name) {
            EngineView::class.java.name -> {
                components.core.engine
                .createView(context, attrs)
                .asView()
            }

            else -> {
                super.onCreateView(parent, name, context, attrs)
            }
        }

    private fun onNonFatalCrash(crash: Crash) {
        Snackbar
            .make(findViewById(android.R.id.content), R.string.crash_report_non_fatal_message, LENGTH_LONG)
            .setAction(R.string.crash_report_non_fatal_action) {
                crashIntegration.sendCrashReport(crash)
            }.show()
    }

    private fun openPopup(webExtensionState: WebExtensionState) {
        val intent = Intent(this, WebExtensionActionPopupActivity::class.java)
        intent.putExtra(WebExtensionActionPopupActivity.EXTRA_EXTENSION_ID, webExtensionState.id)
        intent.putExtra(WebExtensionActionPopupActivity.EXTRA_EXTENSION_NAME, webExtensionState.name)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }
}

/**
 * Some Android document providers return a single selected file in [Intent.getClipData] without
 * setting [Intent.getData]. Android Components' file prompt feature uses [Intent.getData] for a
 * single-file request, so normalize that result before forwarding it while keeping the original
 * intent (and its URI grant flags) intact.
 */
internal fun normalizeFilePickerResult(
    requestCode: Int,
    data: Intent?,
): Intent? {
    if (requestCode != FILE_PICKER_ACTIVITY_REQUEST_CODE || data == null || data.data != null) {
        return data
    }

    val uri: Uri? = data.clipData
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.uri

    return data.apply {
        if (uri != null) {
            setData(uri)
        }
    }
}

private const val FILE_PICKER_ACTIVITY_REQUEST_CODE = 7113
private const val BACKGROUND_STATUS_PREFERENCES = "firefoxlite_background_status"
private const val BACKGROUND_STATUS_PERMISSION_REQUESTED = "notification_permission_requested"
private const val BACKGROUND_STATUS_PERMISSION_REQUEST_CODE = 4102
