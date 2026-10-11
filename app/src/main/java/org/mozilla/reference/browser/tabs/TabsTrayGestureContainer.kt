package org.mozilla.reference.browser.tabs

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import org.mozilla.reference.browser.R

/** List scrolls never dismiss the tray, even when the list is already at its start. */
class TabsTrayGestureContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs) {
    var onSwipeDownFromBottomBar: () -> Unit = {}
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipe = BottomToolbarSwipe(
        maxOf(touchSlop * 2, 50f * resources.displayMetrics.density), touchSlop,
    )
    private val toolbarBounds = Rect()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val toolbar = findViewById<View>(R.id.tabsToolbar)
                val inside = if (toolbar?.isShown == true) {
                    toolbar.getDrawingRect(toolbarBounds)
                    offsetDescendantRectToMyCoords(toolbar, toolbarBounds)
                    toolbarBounds.contains(event.x.toInt(), event.y.toInt())
                } else {
                    false
                }
                swipe.begin(event.rawX, event.rawY, event.getPointerId(0), inside)
            }
            MotionEvent.ACTION_POINTER_DOWN -> swipe.cancel()
            MotionEvent.ACTION_MOVE -> {
                if (swipe.isHandled) return true
                if (swipe.move(event.rawX, event.rawY, event.getPointerId(0))) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    post { onSwipeDownFromBottomBar() }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = swipe.isHandled
                swipe.cancel()
                if (consumed) return true
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
