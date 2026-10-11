package org.mozilla.reference.browser.browser

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.view.WindowInsetsCompat

/** Applies only insets not already handled by an ancestor or adjustResize. */
internal object WindowViewport {
    fun applyInsets(root: View, window: Window, insets: WindowInsetsCompat) {
        if (root.width <= 0 || root.height <= 0) return
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val visible = Rect()
        root.getWindowVisibleDisplayFrame(visible)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.windowManager.currentWindowMetrics.bounds
        } else {
            // The visible frame on older Android already excludes both system UI and IME.
            visible
        }
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val safeLeft = bounds.left + if (modern) bars.left else 0
        val safeTop = bounds.top + if (modern) bars.top else 0
        val safeRight = bounds.right - if (modern) bars.right else 0
        val bottom = if (modern) {
            ViewportGeometry.bottomOverlap(
                location[1] + root.height,
                bounds.bottom,
                bars.bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.isVisible(WindowInsetsCompat.Type.ime()),
            )
        } else {
            (location[1] + root.height - visible.bottom).coerceAtLeast(0)
        }
        val left = (safeLeft - location[0]).coerceIn(0, root.width)
        val right = (location[0] + root.width - safeRight).coerceIn(0, root.width - left)
        val top = (safeTop - location[1]).coerceIn(0, root.height)
        val safeBottom = bottom.coerceIn(0, root.height - top)
        if (root.paddingLeft != left || root.paddingTop != top ||
            root.paddingRight != right || root.paddingBottom != safeBottom
        ) {
            root.setPadding(left, top, right, safeBottom)
        }
    }
}
