/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Context
import android.view.View
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.permission.PermissionRequest
import mozilla.components.concept.engine.prompt.PromptRequest

/**
 * A transient view using the exact runtime, UA and webcompat fixes of the full browser.
 * It never uses Android WebView or mixes normal/private cookie jars. An independent
 * session per changed query prevents late callbacks from choosing a stale URL.
 */
internal class GeckoUrlPreview(
    private val engine: Engine,
    context: Context,
    val privateMode: Boolean,
) : AutoCloseable {
    private val engineView: EngineView = engine.createView(context)
    val view: View = engineView.asView()
    private var session: EngineSession? = null
    private var observer: EngineSession.Observer? = null
    private var closed = false
    var requestedUrl: String? = null
        private set
    var currentUrl: String? = null
        private set

    fun loadUrl(url: String) {
        check(!closed) { "Preview is closed" }
        if (requestedUrl == url && session != null) return
        releaseSession()
        requestedUrl = url
        currentUrl = null
        val next = engine.createSession(private = privateMode)
        session = next
        // Typing a preview must not add every intermediate address to history.
        next.settings.historyTrackingDelegate = null
        next.settings.suspendMediaWhenInactive = true
        val listener = object : EngineSession.Observer {
            override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                if (!closed && session === next && url != "about:blank" && AddressInput.urlOrNull(url) != null) {
                    currentUrl = url
                }
            }
            // Transient previews do not silently accept permissions or leave requests hanging.
            // Opening a full tab enables the browser's normal prompt/permission UI.
            override fun onContentPermissionRequest(permissionRequest: PermissionRequest) {
                permissionRequest.reject()
            }
            override fun onAppPermissionRequest(permissionRequest: PermissionRequest) {
                permissionRequest.reject()
            }
            override fun onPromptRequest(promptRequest: PromptRequest) {
                (promptRequest as? PromptRequest.Dismissible)?.onDismiss?.invoke()
            }
        }
        observer = listener
        next.register(listener)
        try {
            engineView.render(next)
            next.loadUrl(url)
        } catch (error: Exception) {
            releaseSession()
            throw error
        }
    }

    private fun releaseSession() {
        val old = session ?: return
        session = null // Invalidate callbacks before unregistering or releasing its display.
        observer?.let { old.unregister(it) }
        observer = null
        try {
            engineView.release()
        } finally {
            try { old.stopLoading() } finally { old.close() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseSession()
        requestedUrl = null
        currentUrl = null
    }
}
