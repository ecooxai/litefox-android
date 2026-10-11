/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.addons

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.window.WindowRequest
import mozilla.components.lib.state.ext.consumeFrom
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components

/**
 * A movable, minimizable floating window for a WebExtension action popup.
 * The activity is single-task, so clicking an add-on icon restores this window instead of
 * creating another instance.
 */
class WebExtensionActionPopupActivity : AppCompatActivity() {
    private lateinit var webExtensionId: String
    private lateinit var windowTitle: TextView
    private lateinit var contentContainer: FrameLayout

    private var minimized = false
    private var maximized = false
    private var normalWidth = 0
    private var normalHeight = 0
    private var normalX = 0
    private var normalY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_extension_popup)
        configureWindow()
        bindWindowControls()
        showExtension(intent, replaceFragment = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newId = requireNotNull(intent.getStringExtra(EXTRA_EXTENSION_ID))
        if (::webExtensionId.isInitialized && newId == webExtensionId) {
            consumePendingPopupSession(newId)
            updateWindowTitle(intent)
        } else {
            showExtension(intent, replaceFragment = true)
        }
        restoreWindow()
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: android.util.AttributeSet,
    ): View? =
        when (name) {
            EngineView::class.java.name -> {
                val engineView = components.core.engine.createView(context, attrs)
                engineView.asView()
            }

            else -> {
                super.onCreateView(parent, name, context, attrs)
            }
        }

    private fun configureWindow() {
        val metrics = resources.displayMetrics
        normalWidth = (metrics.widthPixels * 0.90f).toInt().coerceAtMost(dp(520))
        normalHeight = (metrics.heightPixels * 0.66f).toInt().coerceAtMost(dp(680))
        normalX = (metrics.widthPixels - normalWidth).coerceAtLeast(0) / 2
        normalY = dp(72)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        applyBounds(normalWidth, normalHeight, normalX, normalY)
    }

    private fun bindWindowControls() {
        windowTitle = findViewById(R.id.addonWindowTitle)
        contentContainer = findViewById(R.id.addonSettingsContainer)

        findViewById<View>(R.id.addonWindowMinimize).setOnClickListener { minimizeWindow() }
        findViewById<View>(R.id.addonWindowMaximize).setOnClickListener { toggleMaximize() }
        findViewById<View>(R.id.addonWindowClose).setOnClickListener { finish() }

        var downRawX = 0f
        var downRawY = 0f
        var downWindowX = 0
        var downWindowY = 0
        windowTitle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = window.attributes.x
                    downWindowY = window.attributes.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!maximized) {
                        val width = window.attributes.width.coerceAtLeast(dp(220))
                        val height = window.attributes.height.coerceAtLeast(dp(48))
                        val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
                        val x = (downWindowX + event.rawX - downRawX).toInt().coerceIn(0, maxX)
                        val y = (downWindowY + event.rawY - downRawY).toInt().coerceIn(0, maxY)
                        applyBounds(width, height, x, y)
                        if (!minimized) {
                            normalX = x
                            normalY = y
                        }
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }
    }

    private fun showExtension(
        intent: Intent,
        replaceFragment: Boolean,
    ) {
        webExtensionId = requireNotNull(intent.getStringExtra(EXTRA_EXTENSION_ID))
        updateWindowTitle(intent)
        if (replaceFragment) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.addonSettingsContainer, WebExtensionActionPopupFragment.create(webExtensionId))
                .commitAllowingStateLoss()
        }
    }

    private fun updateWindowTitle(intent: Intent) {
        val extensionName = intent.getStringExtra(EXTRA_EXTENSION_NAME).orEmpty().ifBlank { "Add-on" }
        title = extensionName
        if (::windowTitle.isInitialized) {
            windowTitle.text = extensionName
        }
    }

    private fun minimizeWindow() {
        if (minimized) {
            restoreWindow()
            return
        }
        if (!maximized) {
            normalX = window.attributes.x
            normalY = window.attributes.y
        }
        minimized = true
        contentContainer.visibility = View.GONE
        applyBounds(dp(250), dp(48), window.attributes.x, window.attributes.y)
    }

    private fun restoreWindow() {
        minimized = false
        contentContainer.visibility = View.VISIBLE
        if (maximized) {
            applyBounds(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0, 0)
        } else {
            applyBounds(normalWidth, normalHeight, normalX, normalY)
        }
    }

    private fun toggleMaximize() {
        if (minimized) {
            restoreWindow()
            return
        }
        if (maximized) {
            maximized = false
            applyBounds(normalWidth, normalHeight, normalX, normalY)
        } else {
            normalX = window.attributes.x
            normalY = window.attributes.y
            maximized = true
            applyBounds(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0, 0)
        }
    }

    private fun applyBounds(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ) {
        window.setLayout(width, height)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            this.width = width
            this.height = height
        }
    }

    private fun consumePendingPopupSession(extensionId: String) {
        components.core.store.dispatch(
            WebExtensionAction.UpdatePopupSessionAction(extensionId, popupSession = null),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    class WebExtensionActionPopupFragment :
        Fragment(),
        EngineSession.Observer {
        private var engineSession: EngineSession? = null
        private lateinit var webExtensionId: String

        private val addonSettingsEngineView: EngineView
            get() = requireView().findViewById<View>(R.id.addonSettingsEngineView) as EngineView

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View? {
            webExtensionId = requireNotNull(arguments?.getString(EXTRA_EXTENSION_ID))
            engineSession = requireContext()
                .components.core.store.state.extensions[webExtensionId]
                ?.popupSession
            return inflater.inflate(R.layout.fragment_add_on_settings, container, false)
        }

        override fun onViewCreated(
            view: View,
            savedInstanceState: Bundle?,
        ) {
            super.onViewCreated(view, savedInstanceState)

            val session = engineSession
            if (session != null) {
                addonSettingsEngineView.render(session)
                consumePopupSession()
            } else {
                consumeFrom(requireContext().components.core.store) { state ->
                    state.extensions[webExtensionId]?.popupSession?.let {
                        if (engineSession == null) {
                            addonSettingsEngineView.render(it)
                            consumePopupSession()
                            engineSession = it
                        }
                    }
                }
            }
        }

        override fun onStart() {
            super.onStart()
            engineSession?.register(this)
        }

        override fun onStop() {
            super.onStop()
            engineSession?.unregister(this)
        }

        override fun onWindowRequest(windowRequest: WindowRequest) {
            if (windowRequest.type == WindowRequest.Type.CLOSE) activity?.finish()
        }

        private fun consumePopupSession() {
            requireContext().components.core.store.dispatch(
                WebExtensionAction.UpdatePopupSessionAction(webExtensionId, popupSession = null),
            )
        }

        companion object {
            fun create(webExtensionId: String) =
                WebExtensionActionPopupFragment().apply {
                    arguments = Bundle().apply { putString(EXTRA_EXTENSION_ID, webExtensionId) }
                }
        }
    }

    companion object {
        const val EXTRA_EXTENSION_ID = "web_extension_id"
        const val EXTRA_EXTENSION_NAME = "web_extension_name"
    }
}
