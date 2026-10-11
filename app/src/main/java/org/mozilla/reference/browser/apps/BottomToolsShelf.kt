/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.apps

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.ImageViewCompat
import mozilla.components.browser.toolbar.BrowserToolbar
import org.mozilla.reference.browser.R

/** A compact navigation/action shelf revealed by swiping left on the URL bar. */
class BottomToolsShelf(
    private val context: Context,
    private val toolbar: BrowserToolbar,
    private val shelf: HorizontalScrollView,
    private val items: LinearLayout,
    private val onBack: () -> Unit,
    private val onForward: () -> Unit,
    private val onZoomOut: () -> Unit,
    private val onZoomIn: () -> Unit,
    private val onFind: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onRefreshLongPress: (View) -> Unit,
    private val onOpenDevTools: () -> Unit,
    private val onOpenDownloads: () -> Unit,
    private val isDesktopMode: () -> Boolean,
    private val onToggleDesktopMode: (Boolean) -> Unit,
) {
    private var pendingDesktopMode: Boolean? = null
    private var pendingDesktopModeAt = 0L

    val isVisible: Boolean
        get() = shelf.visibility == View.VISIBLE

    fun show() {
        rebuild()
        toolbar.visibility = View.GONE
        shelf.visibility = View.VISIBLE
        shelf.scrollTo(0, 0)
    }

    fun hide(): Boolean {
        if (!isVisible) return false
        shelf.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        return true
    }

    private fun rebuild() {
        items.removeAllViews()
        items.addView(createToolButton("Back", mozilla.components.ui.icons.R.drawable.mozac_ic_back_24, onBack))
        items.addView(createToolButton("Forward", mozilla.components.ui.icons.R.drawable.mozac_ic_forward_24, onForward))
        items.addView(createToolButton("Zoom out", R.drawable.toolbar_tool_zoom_out, onZoomOut))
        items.addView(createToolButton("Zoom in", R.drawable.toolbar_tool_zoom_in, onZoomIn))
        items.addView(createToolButton("Find in page", R.drawable.toolbar_tool_find, onFind))
        items.addView(
            createToolButton(
                description = "Refresh",
                icon = mozilla.components.ui.icons.R.drawable.mozac_ic_arrow_clockwise_24,
                action = onRefresh,
                onLongClick = onRefreshLongPress,
            ),
        )
        items.addView(createToolButton("Dev Tools", R.drawable.toolbar_tool_devtools, onOpenDevTools))
        items.addView(createToolButton("Downloads", R.drawable.ic_download, onOpenDownloads))
        items.addView(createDesktopModeButton())
    }

    private fun createDesktopModeButton(): ImageButton {
        var desktopMode = isDesktopMode()
        lateinit var button: ImageButton
        fun updateIcon() {
            button.setImageResource(
                if (desktopMode) R.drawable.toolbar_tool_desktop else R.drawable.toolbar_tool_mobile,
            )
            button.contentDescription = if (desktopMode) {
                "Switch to mobile site"
            } else {
                "Switch to desktop site"
            }
        }

        button = createToolButton(
            description = "Switch to ${if (desktopMode) "mobile" else "desktop"} site",
            icon = if (desktopMode) R.drawable.toolbar_tool_desktop else R.drawable.toolbar_tool_mobile,
            action = {
                val requestedMode = !desktopMode
                val now = SystemClock.uptimeMillis()
                val confirmed = pendingDesktopMode == requestedMode &&
                    now - pendingDesktopModeAt <= MODE_SWITCH_CONFIRMATION_WINDOW_MS
                if (confirmed) {
                    pendingDesktopMode = null
                    desktopMode = requestedMode
                    updateIcon()
                    onToggleDesktopMode(desktopMode)
                } else {
                    pendingDesktopMode = requestedMode
                    pendingDesktopModeAt = now
                    val modeName = if (requestedMode) "desktop" else "mobile"
                    Toast.makeText(
                        context,
                        "Tap again to switch to $modeName site",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        updateIcon()
        return button
    }

    private fun createToolButton(
        description: String,
        icon: Int,
        action: () -> Unit,
        onLongClick: ((View) -> Unit)? = null,
    ): ImageButton {
        return ImageButton(context).apply {
            contentDescription = description
            setImageResource(icon)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(Color.WHITE))
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            background = roundedBackground(0xFF2A2A2A.toInt(), context.dp(12))
            layoutParams = LinearLayout.LayoutParams(context.dp(52), context.dp(50)).apply {
                marginStart = context.dp(4)
                marginEnd = context.dp(4)
            }
            setOnClickListener { action() }
            onLongClick?.let { callback ->
                setOnLongClickListener {
                    callback(this)
                    true
                }
            }
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

}

private const val MODE_SWITCH_CONFIRMATION_WINDOW_MS = 2_500L

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
