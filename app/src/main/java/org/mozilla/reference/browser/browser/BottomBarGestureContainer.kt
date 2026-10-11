/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Owns the URL bar, Apps shelf, and Tools shelf as one gesture surface.
 * Child views still receive ordinary taps; this parent only intercepts a
 * deliberate horizontal or upward drag after it crosses its distance ratio.
 */
class BottomBarGestureContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    enum class Surface {
        URL,
        APPS,
        TOOLS,
    }

    private enum class Gesture {
        NONE,
        SHOW_APPS,
        SHOW_TOOLS,
        SHOW_URL,
        SHOW_TABS,
    }

    var surfaceProvider: () -> Surface = { Surface.URL }
    var onShowApps: () -> Unit = {}
    var onShowTools: () -> Unit = {}
    var onShowUrl: () -> Unit = {}
    var onShowTabs: () -> Unit = {}

    private val horizontalThreshold = maxOf(
        ViewConfiguration.get(context).scaledTouchSlop * 2,
        context.dp(40),
    )
    private val tabsThreshold = context.dp(50)
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var activeGesture = Gesture.NONE

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.rawX
                touchDownY = event.rawY
                activeGesture = Gesture.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeGesture == Gesture.NONE) {
                    val gesture = resolveGesture(event)
                    if (gesture != Gesture.NONE) {
                        activeGesture = gesture
                        cancelChildTouch(event)
                        // Visibility changes rebuild child content. Post them
                        // until this dispatch has finished.
                        post { dispatchGesture(gesture) }
                        return true
                    }
                } else {
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (activeGesture != Gesture.NONE) {
                    activeGesture = Gesture.NONE
                    return true
                }
                activeGesture = Gesture.NONE
            }
        }

        return super.dispatchTouchEvent(event)
    }

    private fun resolveGesture(event: MotionEvent): Gesture {
        val horizontalDistance = event.rawX - touchDownX
        val verticalDistance = event.rawY - touchDownY
        val horizontalDrag = abs(horizontalDistance) >= horizontalThreshold &&
            abs(horizontalDistance) > abs(verticalDistance) * 1.15f
        val upwardDrag = verticalDistance <= -tabsThreshold &&
            abs(verticalDistance) > abs(horizontalDistance) * 1.15f
        val surface = surfaceProvider()

        return when {
            upwardDrag -> Gesture.SHOW_TABS
            !horizontalDrag -> Gesture.NONE
            surface == Surface.URL && horizontalDistance > 0 -> Gesture.SHOW_APPS
            surface == Surface.URL -> Gesture.SHOW_TOOLS
            surface == Surface.APPS && horizontalDistance < 0 -> Gesture.SHOW_URL
            surface == Surface.TOOLS && horizontalDistance > 0 -> Gesture.SHOW_URL
            else -> Gesture.NONE
        }
    }

    private fun cancelChildTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun dispatchGesture(gesture: Gesture) {
        when (gesture) {
            Gesture.SHOW_APPS -> onShowApps()
            Gesture.SHOW_TOOLS -> onShowTools()
            Gesture.SHOW_URL -> onShowUrl()
            Gesture.SHOW_TABS -> onShowTabs()
            Gesture.NONE -> Unit
        }
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
