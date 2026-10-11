/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.apps

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.setPadding
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.feature.tabs.TabsUseCases
import org.mozilla.reference.browser.R

class SavedAppsShelf(
    private val context: Context,
    private val toolbar: BrowserToolbar,
    private val shelf: HorizontalScrollView,
    private val items: LinearLayout,
    private val store: BrowserStore,
    private val tabsUseCases: TabsUseCases,
    private val onAppOpened: () -> Unit = {},
) {
    private val repository = SavedAppsRepository(context)
    private var appMenuPopup: PopupWindow? = null
    private val lastIconGeneration = mutableMapOf<String, Int>()

    val isVisible: Boolean
        get() = shelf.visibility == View.VISIBLE

    fun show() {
        dismissAppMenu()
        dismissKeyboard()
        rebuild()
        toolbar.visibility = View.GONE
        shelf.visibility = View.VISIBLE
        shelf.scrollTo(0, 0)
    }

    fun hide(): Boolean {
        if (!isVisible) return false
        dismissAppMenu()
        shelf.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        return true
    }

    /**
     * Copies the favicon currently reported by each bound app tab into the
     * saved-app record. BrowserStore emits a new state while a page is loading,
     * so this keeps the Apps shelf current without requiring the shelf to be
     * opened again.
     */
    fun refreshFaviconsFromTabs() {
        val apps = repository.getAll()
        if (apps.isEmpty()) return

        val tabs = store.state.tabs
        var changed = false
        apps.forEach { app ->
            val tab = app.tabId?.let { id ->
                tabs.firstOrNull { candidate ->
                    candidate.id == id &&
                        SavedAppsRepository.host(candidate.content.url) == SavedAppsRepository.host(app.url)
                }
            } ?: return@forEach
            val icon = tab.content.icon ?: return@forEach
            val generation = icon.generationId
            if (lastIconGeneration[app.url] == generation) return@forEach
            lastIconGeneration[app.url] = generation
            val iconData = SavedAppsRepository.encodeIcon(icon) ?: return@forEach
            if (iconData != app.iconData) {
                repository.updateIcon(app.url, iconData)
                changed = true
            }
        }

        if (changed && isVisible) refreshVisibleIcons(repository.getAll())
    }

    private fun dismissKeyboard() {
        toolbar.clearFocus()
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(toolbar.windowToken, 0)
    }

    private fun rebuild() {
        dismissAppMenu()
        items.removeAllViews()

        val apps = repository.getAll()
        if (apps.isEmpty()) {
            items.addView(
                TextView(context).apply {
                    text = context.getString(R.string.apps_shelf_empty)
                    setTextColor(Color.LTGRAY)
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(context.dp(16))
                },
            )
            return
        }

        apps.forEach { app -> items.addView(createAppButton(app)) }
    }

    private fun refreshVisibleIcons(apps: List<SavedApp>) {
        if (!isVisible || apps.size != items.childCount) {
            if (isVisible) rebuild()
            return
        }

        apps.forEachIndexed { index, app ->
            val button = items.getChildAt(index) as? ViewGroup ?: return@forEachIndexed
            if (button.childCount > 0) button.removeViewAt(0)
            button.addView(createAppIcon(app), 0)
        }
    }

    @Suppress("ktlint:standard:function-expression-body")
    private fun createAppButton(app: SavedApp): LinearLayout {
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = app.title
            background = null
            setPadding(0)
            layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                marginEnd = context.dp(6)
            }
            addView(createAppIcon(app))

            setOnClickListener { openSavedApp(app) }
            setOnLongClickListener {
                showAppMenu(it, app)
                true
            }
        }
    }

    private fun createAppIcon(app: SavedApp): View {
        val bitmap = SavedAppsRepository.decodeIcon(app.iconData)
        return if (bitmap != null) {
            ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = app.title
                layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
            }
        } else {
            TextView(context).apply {
                text = app.title.firstOrNull()?.uppercase() ?: "•"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = app.title
                layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
            }
        }
    }

    private fun showAppMenu(anchor: View, app: SavedApp) {
        dismissAppMenu()

        val appHost = SavedAppsRepository.host(app.url)
        val tabs = store.state.tabs
        val boundTab = app.tabId?.let { id ->
            tabs.firstOrNull { it.id == id }
                ?.takeIf { SavedAppsRepository.host(it.content.url) == appHost }
        }
        val matchingTabs = tabs.filter { tab ->
            appHost.isNotBlank() && SavedAppsRepository.host(tab.content.url) == appHost
        }
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(6))
            background = roundedBackground(0xFF242424.toInt(), context.dp(10))
        }

        fun addAction(
            label: String,
            action: () -> Unit,
        ) {
            menu.addView(
                TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(context.dp(12), 0, context.dp(12), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        context.dp(46),
                    )
                    setOnClickListener {
                        dismissAppMenu()
                        action()
                    }
                },
            )
        }

        boundTab?.let { tab ->
            addAction(context.getString(R.string.apps_menu_close_tab)) {
                tabsUseCases.removeTab(tab.id)
                repository.clearTabBinding(app.url)
                onAppOpened()
            }
        }
        addAction(context.getString(R.string.apps_menu_new_tab)) {
            val tabId = tabsUseCases.addTab(app.url, selectTab = true)
            repository.bindTab(app.url, tabId)
            onAppOpened()
        }

        if (matchingTabs.isNotEmpty()) {
            menu.addView(
                TextView(context).apply {
                    text = context.getString(R.string.apps_menu_matching_tabs, appHost)
                    setTextColor(Color.LTGRAY)
                    textSize = 12f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        context.dp(32),
                    )
                },
            )
            matchingTabs.forEach { tab ->
                addAction(tab.content.title.ifBlank { tab.content.url }) {
                    repository.bindTab(app.url, tab.id)
                    tabsUseCases.selectTab(tab.id)
                    onAppOpened()
                }
            }
        }

        val width = context.dp(APP_MENU_WIDTH_DP)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val maxX = (screenWidth - menu.measuredWidth - context.dp(4)).coerceAtLeast(context.dp(4))
        val x = (location[0] + (anchor.width - menu.measuredWidth) / 2)
            .coerceIn(context.dp(4), maxX)
        val y = (location[1] - menu.measuredHeight - context.dp(8)).coerceAtLeast(context.dp(4))
        val popup = PopupWindow(
            menu,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = context.dp(8).toFloat()
        }
        appMenuPopup = popup
        popup.setOnDismissListener {
            if (appMenuPopup === popup) appMenuPopup = null
        }
        popup.showAtLocation(shelf, Gravity.TOP or Gravity.START, x, y)
    }

    private fun dismissAppMenu() {
        appMenuPopup?.dismiss()
        appMenuPopup = null
    }

    fun openSavedApp(app: SavedApp) {
        val tabs = store.state.tabs
        val boundTab = app.tabId?.let { id -> tabs.firstOrNull { it.id == id } }
        val appHost = SavedAppsRepository.host(app.url)
        val matchingTab = if (boundTab != null) {
            boundTab.takeIf { SavedAppsRepository.host(it.content.url) == appHost }
        } else {
            tabs.firstOrNull { SavedAppsRepository.host(it.content.url) == appHost }
        }

        if (matchingTab != null) {
            repository.bindTab(app.url, matchingTab.id)
            tabsUseCases.selectTab(matchingTab.id)
        } else {
            val tabId = tabsUseCases.addTab(app.url, selectTab = true)
            repository.bindTab(app.url, tabId)
        }

        // Let the browser restore the Apps surface after the tab-selection
        // state update. This keeps the Apps shelf visible instead of allowing
        // a tab switch to reveal the normal URL toolbar.
        onAppOpened()
    }

    private fun roundedBackground(
        color: Int,
        radius: Int,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun colorFor(value: String): Int {
        val colors = intArrayOf(0xFF3F51B5.toInt(), 0xFF00897B.toInt(), 0xFF7B1FA2.toInt(), 0xFFE65100.toInt())
        return colors[(value.hashCode() and Int.MAX_VALUE) % colors.size]
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private const val APP_MENU_WIDTH_DP = 240
