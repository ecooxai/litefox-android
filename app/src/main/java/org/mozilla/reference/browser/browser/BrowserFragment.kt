/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt
import androidx.core.view.ViewCompat
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.awesomebar.AwesomeBar.Suggestion
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.menu.Orientation
import mozilla.components.feature.awesomebar.AwesomeBarFeature
import mozilla.components.feature.awesomebar.provider.SearchSuggestionProvider
import mozilla.components.feature.downloads.CurrentDownloadState
import mozilla.components.feature.downloads.FileNameOfDuplicateIfAlreadyDownloaded
import mozilla.components.feature.downloads.NegativeActionCallback
import mozilla.components.feature.downloads.OpenFileCallback
import mozilla.components.feature.downloads.PositiveActionCallback
import mozilla.components.feature.readerview.view.ReaderViewControlsBar
import mozilla.components.feature.syncedtabs.SyncedTabsStorageSuggestionProvider
import mozilla.components.feature.tabs.toolbar.TabsToolbarFeature
import mozilla.components.feature.toolbar.WebExtensionToolbarFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.android.view.enterImmersiveMode
import mozilla.components.support.ktx.android.view.exitImmersiveMode
import mozilla.components.ui.tabcounter.TabCounterView
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.apps.BottomToolsShelf
import org.mozilla.reference.browser.apps.ExtensionPinsRepository
import org.mozilla.reference.browser.apps.SavedAppsRepository
import org.mozilla.reference.browser.apps.SavedAppsShelf
import org.mozilla.reference.browser.downloads.DownloadManagerView
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.search.AwesomeBarWrapper
import org.mozilla.reference.browser.settings.PreviewSearchEngine
import org.mozilla.reference.browser.settings.Settings
import org.mozilla.reference.browser.tabs.TabsTrayFragment

/**
 * Fragment used for browsing the web within the main app.
 */
class BrowserFragment :
    BaseBrowserFragment(),
    UserInteractionHandler {
    private val thumbnailsFeature = ViewBoundFeatureWrapper<BrowserThumbnails>()
    private val readerViewFeature = ViewBoundFeatureWrapper<ReaderViewIntegration>()
    private val webExtToolbarFeature = ViewBoundFeatureWrapper<WebExtensionToolbarFeature>()
    private lateinit var savedAppsShelf: SavedAppsShelf
    private lateinit var bottomToolsShelf: BottomToolsShelf
    private var extensionSyncJob: Job? = null
    private var extensionRefreshJob: Job? = null
    private var lastExtensionSyncAt = 0L
    private var pageScale = 1.0f
    private var isBrowserFullscreen = false
    private var fullscreenShelfWasVisible = false
    private var fullscreenExitJob: Job? = null
    private var zoomIndicatorJob: Job? = null
    private var urlPreviewJob: Job? = null
    private var urlPreviewPopup: PopupWindow? = null
    private var urlPreview: GeckoUrlPreview? = null
    private var urlPreviewSearchEngineItems: LinearLayout? = null
    private var urlPreviewQuery = ""
    private var urlPreviewTarget = ""
    private var urlImeLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var lastImeVisible: Boolean? = null
    private var isUrlEditing = false
    private var isExitingUrlEditing = false
    private var persistentUrlEditText: EditText? = null
    private var suppressUrlEditorSync = false
    private var urlKeyboardShowRunnable: Runnable? = null
    private var urlKeyboardShowPending = false
    private var urlEditingStartedAt = 0L
    private var tabCounterLongPressView: View? = null
    private var tabCounterLongPressJob: Job? = null
    private var downloadManagerView: DownloadManagerView? = null
    private var downloadTabId: String? = null
    private var downloadShortcutVisible = false
    private var downloadShortcutJob: Job? = null
    private var downloadShortcutAnimator: ValueAnimator? = null
    private val pendingDownloadConfirmations = linkedMapOf<String, PendingDownloadConfirmation>()
    private var bottomBarSurface = BottomBarGestureContainer.Surface.URL

    private data class PendingDownloadConfirmation(
        val download: CurrentDownloadState,
        val onConfirm: PositiveActionCallback,
        val onCancel: NegativeActionCallback,
    )

    private val awesomeBar: AwesomeBarWrapper
        get() = requireView().findViewById(R.id.awesomeBar)
    private val toolbar: BrowserToolbar
        get() = requireView().findViewById(R.id.toolbar)
    private val bottomBarGestureContainer: BottomBarGestureContainer
        get() = requireView().findViewById(R.id.bottomBarGestureContainer)
    private val engineView: EngineView
        get() = requireView().findViewById<View>(R.id.engineView) as EngineView
    private val readerViewBar: ReaderViewControlsBar
        get() = requireView().findViewById(R.id.readerViewBar)
    private val readerViewAppearanceButton: FloatingActionButton
        get() = requireView().findViewById(R.id.readerViewAppearanceButton)
    private val fullscreenExitButton: TextView
        get() = requireView().findViewById(R.id.fullscreenExitButton)
    private val zoomLevelIndicator: TextView
        get() = requireView().findViewById(R.id.zoomLevelIndicator)
    private val urlEditText: EditText
        get() = persistentUrlEditText
            ?: toolbar.findViewById(
                requireContext().resources.getIdentifier(
                    "mozac_browser_toolbar_edit_url_view",
                    "id",
                    requireContext().packageName,
                ),
            )
    private val urlInputActionOverlay: View
        get() = requireView().findViewById(R.id.urlInputActionOverlay)
    private val urlInputShortcutsOverlay: HorizontalScrollView
        get() = requireView().findViewById(R.id.urlInputShortcutsOverlay)
    private val urlInputShortcutsItems: LinearLayout
        get() = requireView().findViewById(R.id.urlInputShortcutsItems)
    private val urlInputTabsButton: View
        get() = requireView().findViewById(R.id.urlInputTabsButton)
    private val urlInputMenuButton: View
        get() = requireView().findViewById(R.id.urlInputMenuButton)
    private val downloadShortcutButton: ImageButton
        get() = requireView().findViewById(R.id.downloadShortcutButton)
    private val urlInputTabCount: TextView
        get() = requireView().findViewById(R.id.urlInputTabCount)

    override val shouldUseComposeUI: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(
            getString(R.string.pref_key_compose_ui),
            false,
        )

    override val supportsAppsShelf: Boolean = true

    @Suppress("LongMethod")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        // Register these callbacks before BaseBrowserFragment constructs ToolbarIntegration.
        setPinnedExtensionsRefresher { requestExtensionToolbarSync(immediate = true) }
        setExtensionOpener { extensionId ->
            exitUrlEditing()
            webExtToolbarFeature.withFeature { feature ->
                ExtensionToolbarCompat.open(feature, extensionId)
            }
        }
        setFullscreenOpener {
            exitUrlEditing()
            enterBrowserFullscreen()
        }
        setDownloadsOpener {
            exitUrlEditing()
            openDownloadsTab()
        }
        setDownloadConfirmationHandler(::showDownloadConfirmation)
        setDownloadConfirmationDismissHandler(::dismissDownloadConfirmations)
        setDownloadStartedHandler(::showDownloadShortcut)
        super.onViewCreated(view, savedInstanceState)

        // BrowserToolbar normally swaps its display toolbar for a separate edit
        // toolbar when the URL is tapped. Keep the URL editor inside the
        // display toolbar instead, so the page and every bottom-bar action stay
        // in the same UI while the keyboard is shown.
        installPersistentUrlEditor()

        downloadShortcutButton.setImageResource(R.drawable.ic_download)
        downloadShortcutButton.imageTintList = ColorStateList.valueOf(DOWNLOAD_ICON_BLUE)
        downloadShortcutButton.setOnClickListener {
            hideDownloadShortcut()
            exitUrlEditing()
            openDownloadsTab()
        }
        updateDownloadShortcutButton()

        bottomToolsShelf = BottomToolsShelf(
            context = requireContext(),
            toolbar = toolbar,
            shelf = view.findViewById(R.id.bottomToolsShelf),
            items = view.findViewById(R.id.bottomToolsShelfItems),
            onBack = {
                exitUrlEditing()
                requireComponents.useCases.sessionUseCases.goBack.invoke()
            },
            onForward = {
                exitUrlEditing()
                requireComponents.useCases.sessionUseCases.goForward.invoke()
            },
            onZoomOut = {
                exitUrlEditing()
                changePageScale(-ZOOM_STEP)
            },
            onZoomIn = {
                exitUrlEditing()
                changePageScale(ZOOM_STEP)
            },
            onFind = {
                exitUrlEditing()
                FindInPageIntegration.launch?.invoke()
            },
            onRefresh = {
                exitUrlEditing()
                requireComponents.useCases.sessionUseCases.reload.invoke()
            },
            onRefreshLongPress = {
                exitUrlEditing()
                showRefreshSubmenu(it)
            },
            onOpenDevTools = {
                exitUrlEditing()
                openDeveloperTools()
            },
            onOpenDownloads = {
                exitUrlEditing()
                openDownloadsTab()
            },
            isDesktopMode = {
                requireComponents.core.store.state.selectedTab?.content?.desktopMode == true
            },
            onToggleDesktopMode = { desktopMode ->
                exitUrlEditing()
                requireComponents.useCases.sessionUseCases.requestDesktopSite.invoke(desktopMode)
            },
        )

        savedAppsShelf = SavedAppsShelf(
            context = requireContext(),
            toolbar = toolbar,
            shelf = view.findViewById<HorizontalScrollView>(R.id.appsShelf),
            items = view.findViewById<LinearLayout>(R.id.appsShelfItems),
            store = requireComponents.core.store,
            tabsUseCases = requireComponents.useCases.tabsUseCases,
            onAppOpened = ::showAppsShelf,
        )
        installBottomBarGestureContainer()
        setAppsShelfOpener {
            showAppsShelf()
        }

        fullscreenExitButton.setOnClickListener {
            exitUrlEditing()
            exitBrowserFullscreen()
        }
        installFullscreenScrollDetector()

        AwesomeBarFeature(
            awesomeBar,
            toolbar,
            engineView,
            null,
            null,
            {
                // Keep editing in the existing bottom toolbar. The default
                // AwesomeBarFeature callback hides GeckoView and opens a full
                // screen suggestion surface, which is not wanted here.
                awesomeBar.visibility = View.GONE
                engineView.asView().visibility = View.VISIBLE
            },
            {
                awesomeBar.visibility = View.GONE
                engineView.asView().visibility = View.VISIBLE
            },
        )
            .addSearchProvider(
                requireComponents.core.store,
                requireComponents.useCases.searchUseCases.defaultSearch,
                fetchClient = requireComponents.core.client,
                mode = SearchSuggestionProvider.Mode.MULTIPLE_SUGGESTIONS,
                engine = requireComponents.core.engine,
                limit = 5,
                filterExactMatch = true,
            ).addSessionProvider(
                resources,
                requireComponents.core.store,
                requireComponents.useCases.tabsUseCases.selectTab,
            ).addHistoryProvider(
                requireComponents.core.historyStorage,
                requireComponents.useCases.sessionUseCases.loadUrl,
            ).addClipboardProvider(requireContext(), requireComponents.useCases.sessionUseCases.loadUrl)

        // We cannot really add a `addSyncedTabsProvider` to `AwesomeBarFeature` coz that would create
        // a dependency on feature-syncedtabs (which depends on Sync).
        awesomeBar.addProviders(
            SyncedTabsStorageSuggestionProvider(
                requireComponents.backgroundServices.syncedTabsStorage,
                requireComponents.useCases.tabsUseCases.addTab,
                requireComponents.core.icons,
            ),
        )
        awesomeBar.setOnRemoveSuggestionButtonClicked {
            awesomeBar.addHiddenSuggestion(it)
            (it.suggestion as? Suggestion)?.let { s -> deleteHistorySuggestion(s) }
        }

        installUrlEntryPreview()
        installUrlFocusDismissal(view)

        TabsToolbarFeature(
            toolbar = toolbar,
            sessionId = sessionId,
            store = requireComponents.core.store,
            showTabs = ::showTabs,
            lifecycleOwner = this,
        )
        installTabCounterLongPress()

        thumbnailsFeature.set(
            feature = BrowserThumbnails(
                requireContext(),
                engineView,
                requireComponents.core.store,
            ),
            owner = this,
            view = view,
        )

        readerViewFeature.set(
            feature = ReaderViewIntegration(
                requireContext(),
                requireComponents.core.engine,
                requireComponents.core.store,
                toolbar,
                readerViewBar,
                readerViewAppearanceButton,
            ),
            owner = this,
            view = view,
        )

        webExtToolbarFeature.set(
            feature = WebExtensionToolbarFeature(
                toolbar,
                requireContext().components.core.store,
            ),
            owner = this,
            view = view,
        )
        // Wait until the toolbar and its feature lifecycle are attached before changing
        // extension actions. Some Android Components versions are not ready during onViewCreated.
        view.post { requestExtensionToolbarSync() }
        lifecycleScope.launch {
            requireComponents.core.store.flow().collect {
                requestExtensionToolbarSync()
                restoreDirectDownloadsTabIfNeeded()
                updateDownloadTabPresentation()
                ensureDownloadTabFirst()
                savedAppsShelf.refreshFaviconsFromTabs()
                syncUrlEditorFromSession()
                restoreBottomBarSurface()
            }
        }
        extensionRefreshJob?.cancel()
        extensionRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000L)
                view.post { refreshExtensionToolbarActions() }
            }
        }

        engineView.setDynamicToolbarMaxHeight(0)
    }

    override fun onResume() {
        super.onResume()
        applyConfiguredSystemBars()
    }

    private fun applyConfiguredSystemBars() {
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val systemBars = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        var configuredHiddenBars = 0
        if (Settings.isStatusBarHidden(requireContext())) {
            configuredHiddenBars = configuredHiddenBars or WindowInsetsCompat.Type.statusBars()
        }
        if (Settings.isNavigationBarHidden(requireContext())) {
            configuredHiddenBars = configuredHiddenBars or WindowInsetsCompat.Type.navigationBars()
        }

        controller.show(systemBars)
        if (configuredHiddenBars != 0) controller.hide(configuredHiddenBars)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun requestExtensionToolbarSync(immediate: Boolean = false) {
        if (immediate) {
            extensionSyncJob?.cancel()
            extensionSyncJob = null
            lastExtensionSyncAt = SystemClock.uptimeMillis()
            view?.post { syncExtensionToolbarActions() }
            return
        }

        if (extensionSyncJob?.isActive == true) return

        val elapsed = SystemClock.uptimeMillis() - lastExtensionSyncAt
        val wait = if (lastExtensionSyncAt == 0L) 0L else (5_000L - elapsed).coerceAtLeast(0L)
        extensionSyncJob = lifecycleScope.launch {
            if (wait > 0L) delay(wait)
            lastExtensionSyncAt = SystemClock.uptimeMillis()
            view?.post { syncExtensionToolbarActions() }
            extensionSyncJob = null
        }
    }

    private fun syncExtensionToolbarActions() {
        try {
            val pins = ExtensionPinsRepository(requireContext())
            webExtToolbarFeature.withFeature { feature ->
                ExtensionToolbarCompat.sync(feature, toolbar, pins.getPinnedIds())
            }
            // Adding browser actions can rebuild the toolbar's action views.
            // Reattach the tab-counter gesture after that rebuild as well.
            view?.post { if (isAdded && view != null) installTabCounterLongPress() }
        } catch (error: Throwable) {
            // A toolbar API mismatch must not prevent the browser from launching.
            Log.e("FirefoxLite", "Unable to synchronize extension toolbar actions", error)
        }
    }

    private fun refreshExtensionToolbarActions() {
        try {
            ExtensionToolbarCompat.refresh(toolbar)
        } catch (error: Throwable) {
            Log.e("FirefoxLite", "Unable to refresh extension toolbar actions", error)
        }
    }

    override fun onDestroyView() {
        urlImeLayoutListener?.let { listener ->
            view?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        urlImeLayoutListener = null
        lastImeVisible = null
        extensionSyncJob?.cancel()
        extensionRefreshJob?.cancel()
        tabCounterLongPressJob?.cancel()
        tabCounterLongPressJob = null
        tabCounterLongPressView?.setOnLongClickListener(null)
        tabCounterLongPressView = null
        fullscreenExitJob?.cancel()
        zoomIndicatorJob?.cancel()
        downloadShortcutJob?.cancel()
        downloadShortcutAnimator?.cancel()
        downloadShortcutAnimator = null
        urlPreviewJob?.cancel()
        urlKeyboardShowRunnable?.let { persistentUrlEditText?.removeCallbacks(it) }
        urlKeyboardShowRunnable = null
        urlKeyboardShowPending = false
        urlEditingStartedAt = 0L
        dismissUrlPreview()
        pendingDownloadConfirmations.clear()
        downloadShortcutVisible = false
        persistentUrlEditText = null
        downloadManagerView?.release()
        downloadManagerView = null
        super.onDestroyView()
    }

    private fun installUrlFocusDismissal(root: View) {
        // Android's IME consumes the first Back press itself. Watch the IME
        // transition so the URL editor does not remain focused after the
        // keyboard has closed.
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val imeVisible = ViewCompat.getRootWindowInsets(root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            updateUrlPreviewSize()
            val wasImeVisible = lastImeVisible
            lastImeVisible = imeVisible
            if (imeVisible) {
                // A clean URL-bar tap is still activating the IME until the
                // first visible-insets callback arrives. Do not interpret that
                // activation transition as a user dismissal.
                urlKeyboardShowPending = false
            }
            val activationGraceActive = SystemClock.uptimeMillis() - urlEditingStartedAt <
                URL_EDITOR_ACTIVATION_GRACE_MS
            if (wasImeVisible == true &&
                !imeVisible &&
                isUrlEditing &&
                !isExitingUrlEditing &&
                !urlKeyboardShowPending &&
                !activationGraceActive
            ) {
                root.post {
                    if (isUrlEditing &&
                        !isImeVisible(root) &&
                        !urlKeyboardShowPending &&
                        SystemClock.uptimeMillis() - urlEditingStartedAt >= URL_EDITOR_ACTIVATION_GRACE_MS
                    ) {
                        exitUrlEditing()
                    }
                }
            }
        }
        urlImeLayoutListener = listener
        lastImeVisible = isImeVisible(root)
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun isImeVisible(root: View): Boolean =
        ViewCompat.getRootWindowInsets(root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun installPersistentUrlEditor() {
        if (persistentUrlEditText != null) return

        val resources = requireContext().resources
        val packageName = requireContext().packageName
        val originId = resources.getIdentifier(
            "mozac_browser_toolbar_origin_view",
            "id",
            packageName,
        )
        val urlViewId = resources.getIdentifier(
            "mozac_browser_toolbar_url_view",
            "id",
            packageName,
        )
        val titleViewId = resources.getIdentifier(
            "mozac_browser_toolbar_title_view",
            "id",
            packageName,
        )
        val origin = toolbar.findViewById<ViewGroup>(originId) ?: return
        val displayUrl = origin.findViewById<TextView>(urlViewId)
        val displayTitle = origin.findViewById<TextView>(titleViewId)
        val editor = EditText(requireContext()).apply {
            setSingleLine(true)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            gravity = Gravity.CENTER_VERTICAL
            background = null
            setPadding(dp(4), 0, dp(4), 0)
            setTextColor(displayUrl?.currentTextColor ?: Color.WHITE)
            setHintTextColor(0xFFB7B2C0.toInt())
            hint = getString(R.string.toolbar_hint)
            displayUrl?.let {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it.textSize)
                typeface = it.typeface
                setText(it.text)
            }
        }

        // BrowserToolbar continues to update its stock URL/title views, but
        // those views no longer draw or receive taps. The editor below is the
        // only URL element throughout display and input states.
        displayUrl?.visibility = View.GONE
        displayTitle?.visibility = View.GONE
        origin.addView(
            editor,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        persistentUrlEditText = editor

        // Returning false prevents BrowserToolbar from entering its separate
        // edit toolbar when the URL area is tapped.
        toolbar.display.onUrlClicked = {
            beginUrlEditing()
            false
        }
    }

    private fun syncUrlEditorFromSession(force: Boolean = false) {
        val editor = persistentUrlEditText ?: return
        if (!force && editor.hasFocus()) return

        val url = requireComponents.core.store.state.selectedTab?.content?.url.orEmpty()
        if (editor.text?.toString() == url) return

        suppressUrlEditorSync = true
        editor.setText(url)
        editor.setSelection(editor.length())
        suppressUrlEditorSync = false
    }

    private fun beginUrlEditing() {
        val editor = persistentUrlEditText ?: return
        if (!editor.hasFocus()) syncUrlEditorFromSession()

        urlEditingStartedAt = SystemClock.uptimeMillis()
        isUrlEditing = true
        awesomeBar.visibility = View.GONE
        engineView.asView().visibility = View.VISIBLE
        editor.isFocusableInTouchMode = true
        editor.showSoftInputOnFocus = true
        editor.requestFocus()
        editor.selectAll()
        urlKeyboardShowPending = !isImeVisible(requireView())
        keepUrlKeyboardVisible()
    }

    private fun exitUrlEditing() {
        if ((!isUrlEditing && !urlEditText.hasFocus()) || isExitingUrlEditing) return

        isExitingUrlEditing = true
        isUrlEditing = false
        urlPreviewJob?.cancel()
        urlPreviewJob = null
        urlPreviewQuery = ""
        dismissUrlPreview()
        updateDownloadShortcutButton()
        urlInputShortcutsOverlay.visibility = View.GONE
        awesomeBar.visibility = View.GONE
        engineView.asView().visibility = View.VISIBLE

        val editText = urlEditText
        val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        urlKeyboardShowRunnable?.let { editText.removeCallbacks(it) }
        urlKeyboardShowRunnable = null
        urlKeyboardShowPending = false
        inputMethodManager.hideSoftInputFromWindow(editText.windowToken, 0)
        editText.clearFocus()
        toolbar.clearFocus()
        syncUrlEditorFromSession(force = true)
        isExitingUrlEditing = false
    }

    private fun installUrlEntryPreview() {
        val editText = urlEditText

        editText.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    if (suppressUrlEditorSync || !editText.hasFocus()) return

                    // The stock AwesomeBar is a full-screen suggestion layer.
                    // Keep it out of the way while editing so the current
                    // Gecko page remains visible behind the keyboard/preview.
                    awesomeBar.visibility = View.GONE

                    val query = s?.toString()?.trim().orEmpty()
                    if (query == urlPreviewQuery && urlPreviewPopup == null) return
                    urlPreviewQuery = query
                    urlPreviewJob?.cancel()

                    if (query.isBlank()) {
                        dismissUrlPreview()
                        return
                    }

                    urlPreviewJob = lifecycleScope.launch {
                        delay(URL_PREVIEW_DELAY_MS)
                        if (isActive && editText.hasFocus() && query == urlPreviewQuery) {
                            showUrlPreview(query)
                        }
                    }
                }
            },
        )

        editText.setOnFocusChangeListener { _, isEditing ->
            if (isEditing) {
                if (!isUrlEditing) urlEditingStartedAt = SystemClock.uptimeMillis()
                isUrlEditing = true
                awesomeBar.visibility = View.GONE
                engineView.asView().visibility = View.VISIBLE
                urlPreviewQuery = editText.text?.toString()?.trim().orEmpty()
                urlInputTabCount.text = requireComponents.core.store.state.tabs.size.toString()
                // Keep the normal bottom toolbar unchanged. Only the editor
                // focus and IME state change; no second toolbar or shortcut
                // surface is shown above it.
                urlInputActionOverlay.visibility = View.GONE
                urlInputShortcutsOverlay.visibility = View.GONE
                keepUrlKeyboardVisible()
            } else {
                // Android can briefly move focus while it activates the IME
                // or resizes the window. Recover from that transient loss;
                // only an explicit exit or a real post-activation loss should
                // clear the URL editor.
                editText.postDelayed({
                    if (editText.hasFocus() || isExitingUrlEditing || !isUrlEditing) return@postDelayed
                    if (urlKeyboardShowPending ||
                        SystemClock.uptimeMillis() - urlEditingStartedAt < URL_EDITOR_ACTIVATION_GRACE_MS
                    ) {
                        editText.requestFocus()
                        keepUrlKeyboardVisible()
                    } else {
                        exitUrlEditing()
                    }
                }, URL_EDITOR_FOCUS_LOSS_GRACE_MS)
            }
        }

        editText.setOnClickListener { beginUrlEditing() }

        // Commit directly from the persistent URL element. This never invokes
        // BrowserToolbar.editMode(), so Enter cannot swap in the stock editor.
        editText.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                enterPressed
            ) {
                commitUrlInput()
            } else {
                false
            }
        }

        editText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && isUrlEditing) {
                exitUrlEditing()
                true
            } else if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                commitUrlInput()
            } else {
                false
            }
        }

        urlInputTabsButton.setOnClickListener {
            exitUrlEditing()
            showTabs()
        }
        urlInputTabsButton.setOnLongClickListener {
            handleTabButtonLongPress()
        }
        urlInputMenuButton.setOnClickListener {
            // Use the same menu controller as the real toolbar menu, but anchor
            // it to the visible edit-mode button.
            toolbar.display.menuController?.show(urlInputMenuButton, Orientation.UP, false)
        }

        // Keep the normal URL/search behavior when no preview is open. Once a
        // preview exists, Enter adds that page as a background tab and leaves
        // the editor and keyboard available for the next search.
        toolbar.setOnUrlCommitListener { enteredUrl ->
            commitUrlInput(enteredUrl)
        }
    }

    private fun installTabCounterLongPress() {
        tabCounterLongPressJob?.cancel()
        tabCounterLongPressJob = lifecycleScope.launch {
            repeat(TAB_COUNTER_INSTALL_ATTEMPTS) {
                val tabCounter = findTabCounterView(toolbar)
                if (tabCounter != null) {
                    if (tabCounterLongPressView !== tabCounter) {
                        tabCounterLongPressView?.setOnLongClickListener(null)
                        tabCounter.setOnLongClickListener {
                            handleTabButtonLongPress()
                        }
                        tabCounterLongPressView = tabCounter
                    }
                    return@launch
                }
                delay(TAB_COUNTER_INSTALL_DELAY_MS)
            }
        }
    }

    private fun findTabCounterView(root: View): TabCounterView? {
        if (root is TabCounterView) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findTabCounterView(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun handleTabButtonLongPress(): Boolean {
        exitUrlEditing()
        val privateMode = requireComponents.core.store.state.selectedTab?.content?.private == true
        val blankTab = requireComponents.core.store.state.tabs.firstOrNull { tab ->
            tab.content.private == privateMode &&
                tab.content.url.trim().equals(ABOUT_BLANK_URL, ignoreCase = true)
        }
        if (blankTab != null) {
            requireComponents.useCases.tabsUseCases.selectTab(blankTab.id)
        } else {
            requireComponents.useCases.tabsUseCases.addTab(
                url = ABOUT_BLANK_URL,
                selectTab = true,
                private = privateMode,
            )
        }
        return true
    }

    private fun commitUrlInput(enteredUrl: String = urlEditText.text?.toString().orEmpty()): Boolean {
        val value = enteredUrl.trim()
        if (value.isBlank()) return false

        if (isDownloadsUrl(value)) {
            dismissUrlPreview()
            openDownloadsTab()
            return true
        }

        // Enter is navigation, not a hidden background-tab creation. Always use
        // the submitted input, never a stale/partially loaded preview location.
        dismissUrlPreview()
        loadUrlOrSearch(value)
        exitUrlEditing()
        return true
    }

    private fun loadUrlOrSearch(value: String) {
        if (isDownloadsUrl(value)) {
            openDownloadsTab()
            return
        }

        val url = AddressInput.urlOrNull(value)
        if (url != null) {
            requireComponents.useCases.sessionUseCases.loadUrl.invoke(url)
        } else {
            requireComponents.useCases.searchUseCases.defaultSearch.invoke(
                searchTerms = value,
                searchEngine = null,
                parentSessionId = null,
            )
        }
    }

    private fun showUrlPreview(query: String) {
        if (!isAdded || query.isBlank()) return

        val privateMode = requireComponents.core.store.state.selectedTab?.content?.private == true
        if (urlPreview?.privateMode?.let { it != privateMode } == true) dismissUrlPreview()
        val previewUrl = previewUrlFor(query)
        urlPreviewTarget = previewUrl

        if (urlPreviewPopup == null) {
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xF9282828.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                clipToOutline = true
            }
            val header = LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF242424.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(URL_PREVIEW_HEADER_DP),
                )
            }
            val closeButton = TextView(requireContext()).apply {
                text = "×"
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.CENTER
                isFocusable = false
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { dismissUrlPreview() }
            }
            val searchEnginesScroll = HorizontalScrollView(requireContext()).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val searchEngineItems = LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            searchEnginesScroll.addView(
                searchEngineItems,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            urlPreviewSearchEngineItems = searchEngineItems
            val addButton = TextView(requireContext()).apply {
                text = "+"
                setTextColor(Color.WHITE)
                textSize = 25f
                gravity = Gravity.CENTER
                isFocusable = false
                contentDescription = "Add preview to tabs"
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { addPreviewToTabs(urlPreviewQuery, selectTab = true) }
            }
            header.addView(
                closeButton,
                LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            header.addView(searchEnginesScroll)
            header.addView(
                addButton,
                LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            rebuildPreviewSearchEngineButtons()

            val preview = GeckoUrlPreview(requireComponents.core.engine, requireContext(), privateMode)
            preview.view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            container.addView(header)
            container.addView(preview.view)
            urlPreview = preview
            val popup = PopupWindow(
                container,
                previewSizePx(),
                previewSizePx(),
                false,
            ).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isTouchable = true
                isFocusable = false
                isOutsideTouchable = false
                inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                elevation = dp(8).toFloat()
            }
            popup.setOnDismissListener {
                if (urlPreviewPopup !== popup) return@setOnDismissListener

                urlPreviewPopup = null
                urlPreview?.close()
                urlPreview = null
                urlPreviewSearchEngineItems = null

                // Some IME/window-manager transitions dismiss a non-focusable
                // popup while the URL editor is still active. Recreate it
                // instead of making the preview disappear after a few seconds.
                if (isUrlEditing && urlPreviewQuery.isNotBlank()) {
                    val queryToRestore = urlPreviewQuery
                    urlPreviewJob?.cancel()
                    urlPreviewJob = lifecycleScope.launch {
                        delay(URL_PREVIEW_REOPEN_DELAY_MS)
                        if (isActive && isUrlEditing && urlPreviewQuery == queryToRestore) {
                            showUrlPreview(queryToRestore)
                        }
                    }
                }
            }
            urlPreviewPopup = popup
            popup.showAtLocation(
                requireView(),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                0,
                dp(8),
            )
            updateUrlPreviewSize()
        }

        urlPreview?.loadUrl(previewUrl)
        keepUrlKeyboardVisible()
    }

    private fun keepUrlKeyboardVisible() {
        val editText = urlEditText
        editText.requestFocus()
        val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!isImeVisible(editText)) urlKeyboardShowPending = true
        urlKeyboardShowRunnable?.let { editText.removeCallbacks(it) }
        val showKeyboard = Runnable {
            if (isUrlEditing && editText.hasFocus()) {
                inputMethodManager.showSoftInput(editText, 0)
            }
        }
        urlKeyboardShowRunnable = showKeyboard
        // Retry after the toolbar/preview window transition so the IME is not
        // dismissed immediately after a URL tap.
        editText.post(showKeyboard)
        editText.postDelayed(showKeyboard, 160L)
    }

    private fun updateUrlPreviewSize() {
        val popup = urlPreviewPopup ?: return
        if (!popup.isShowing) return
        popup.update(previewSizePx(), previewHeightPx())
    }

    private fun addPreviewToTabs(
        fallbackQuery: String,
        selectTab: Boolean = false,
    ) {
        val target = previewUrlFor(fallbackQuery)
        val privateMode = requireComponents.core.store.state.selectedTab?.content?.private == true
        val matchingPreview = urlPreview?.takeIf { it.privateMode == privateMode }
        val url = AddressInput.previewTarget(target, matchingPreview?.requestedUrl, matchingPreview?.currentUrl)
        requireComponents.useCases.tabsUseCases.addTab(url = url, selectTab = selectTab, private = privateMode)
        if (selectTab) {
            dismissUrlPreview()
            exitUrlEditing()
        }
        urlInputTabCount.post {
            urlInputTabCount.text = requireComponents.core.store.state.tabs.size.toString()
        }
    }

    private fun rebuildUrlInputShortcuts() {
        urlInputShortcutsItems.removeAllViews()
        urlInputShortcutsItems.addView(
            createUrlShortcut(
                label = "⇩",
                description = "Open Downloads",
                color = 0xFF3465A4.toInt(),
            ) {
                exitUrlEditing()
                openDownloadsTab()
            },
        )

        SavedAppsRepository(requireContext()).getAll().forEach { app ->
            urlInputShortcutsItems.addView(
                createUrlShortcut(
                    label = app.title.firstOrNull()?.uppercase() ?: "•",
                    description = app.title,
                    color = shortcutColor(app.url),
                ) {
                    exitUrlEditing()
                    savedAppsShelf.openSavedApp(app)
                },
            )
        }
    }

    private fun createUrlShortcut(
        label: String,
        description: String,
        color: Int,
        action: () -> Unit,
    ): TextView = TextView(requireContext()).apply {
        text = label
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = description
        isFocusable = false
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
        setOnClickListener { action() }
    }

    private fun shortcutColor(value: String): Int {
        val colors = intArrayOf(0xFF3F51B5.toInt(), 0xFF00897B.toInt(), 0xFF7B1FA2.toInt(), 0xFFE65100.toInt())
        return colors[(value.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    private fun dismissUrlPreview() {
        val popup = urlPreviewPopup
        urlPreviewPopup = null
        popup?.setOnDismissListener(null)
        popup?.dismiss()
        urlPreview?.close()
        urlPreview = null
        urlPreviewSearchEngineItems = null
        urlPreviewTarget = ""
    }

    private fun previewUrlFor(query: String): String {
        val value = query.trim()
        return AddressInput.urlOrNull(value) ?: run {
            val engine = Settings.getDefaultPreviewSearchEngine(requireContext())
            engine.urlPrefix + URLEncoder.encode(value, "UTF-8")
        }
    }

    private fun previewSizePx(): Int {
        val requestedSizePx = dp(URL_PREVIEW_SIZE_DP)
        val screenMarginPx = dp(URL_PREVIEW_SCREEN_MARGIN_DP)
        val availableWidthPx = (resources.displayMetrics.widthPixels - screenMarginPx * 2).coerceAtLeast(dp(1))
        return requestedSizePx.coerceAtMost(availableWidthPx)
    }

    private fun previewHeightPx(): Int {
        val requestedSizePx = dp(URL_PREVIEW_SIZE_DP)
        val root = view ?: return requestedSizePx
        val insets = ViewCompat.getRootWindowInsets(root)
        val imeBottom = if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        } else {
            0
        }
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val availableBottom = if (imeBottom > 0) {
            (rootLocation[1] + root.height - imeBottom).coerceAtLeast(rootLocation[1])
        } else {
            rootLocation[1] + root.height
        }
        val availableHeight = availableBottom - rootLocation[1] - dp(URL_PREVIEW_SCREEN_MARGIN_DP * 2)
        return requestedSizePx.coerceAtMost(
            availableHeight.coerceAtLeast(dp(URL_PREVIEW_HEADER_DP + 1)),
        )
    }

    private fun rebuildPreviewSearchEngineButtons() {
        val items = urlPreviewSearchEngineItems ?: return
        items.removeAllViews()
        Settings.getPreviewSearchEngines(requireContext()).forEach { engine ->
            items.addView(createPreviewSearchEngineButton(engine))
        }
    }

    private fun createPreviewSearchEngineButton(engine: PreviewSearchEngine): TextView =
        TextView(requireContext()).apply {
            text = engine.iconLabel
            textSize = if (engine.iconLabel.length > 1) 12f else 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Search with ${engine.name}"
            isFocusable = false
            val selected = Settings.getDefaultPreviewSearchEngine(requireContext()).id == engine.id
            background = GradientDrawable().apply {
                setColor(searchEngineColor(engine.id))
                setStroke(dp(if (selected) 2 else 1), if (selected) Color.WHITE else 0xFF626A76.toInt())
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(32)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            setOnClickListener {
                Settings.setDefaultPreviewSearchEngine(requireContext(), engine.id)
                urlPreviewTarget = previewUrlFor(urlPreviewQuery)
                urlPreview?.loadUrl(urlPreviewTarget)
                rebuildPreviewSearchEngineButtons()
            }
        }

    private fun searchEngineColor(engineId: String): Int {
        val colors = intArrayOf(0xFF4285F4.toInt(), 0xFF008373.toInt(), 0xFF5B4BB7.toInt(), 0xFFDE5833.toInt())
        return colors[(engineId.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    private fun installFullscreenScrollDetector() {
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var touchDownY = 0f
        var scrollDetected = false

        engineView.asView().setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A page tap is outside the URL editor. Clear the editor
                    // before returning the event to GeckoView.
                    if (isUrlEditing) exitUrlEditing()
                    touchDownY = event.rawY
                    scrollDetected = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isBrowserFullscreen &&
                        !scrollDetected &&
                        touchDownY - event.rawY > touchSlop
                    ) {
                        scrollDetected = true
                        showFullscreenExitButton()
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    scrollDetected = false
                }
            }
            false
        }
    }

    private fun enterBrowserFullscreen() {
        if (isBrowserFullscreen) return

        fullscreenShelfWasVisible = bottomBarGestureContainer.visibility == View.VISIBLE
        isBrowserFullscreen = true
        fullscreenExitJob?.cancel()
        fullscreenExitButton.visibility = View.GONE
        activity?.enterImmersiveMode()
        bottomBarGestureContainer.visibility = View.GONE
        engineView.setDynamicToolbarMaxHeight(0)
    }

    private fun exitBrowserFullscreen() {
        if (!isBrowserFullscreen) return

        isBrowserFullscreen = false
        fullscreenExitJob?.cancel()
        fullscreenExitButton.visibility = View.GONE
        activity?.exitImmersiveMode()
        applyConfiguredSystemBars()
        engineView.setDynamicToolbarMaxHeight(0)

        if (fullscreenShelfWasVisible) {
            bottomBarGestureContainer.visibility = View.VISIBLE
        } else {
            toolbar.visibility = View.VISIBLE
        }
    }

    private fun installBottomBarGestureContainer() {
        bottomBarGestureContainer.surfaceProvider = {
            bottomBarSurface
        }
        bottomBarGestureContainer.onShowApps = ::showAppsShelf
        bottomBarGestureContainer.onShowTools = ::showTools
        bottomBarGestureContainer.onShowUrl = ::showUrlBar
        bottomBarGestureContainer.onShowTabs = {
            exitUrlEditing()
            showTabs()
        }
    }

    private fun showAppsShelf() {
        bottomBarSurface = BottomBarGestureContainer.Surface.APPS
        exitUrlEditing()
        bottomToolsShelf.hide()
        savedAppsShelf.show()
    }

    private fun showUrlBar() {
        bottomBarSurface = BottomBarGestureContainer.Surface.URL
        exitUrlEditing()
        savedAppsShelf.hide()
        bottomToolsShelf.hide()
        toolbar.visibility = View.VISIBLE
    }

    private fun showTools() {
        bottomBarSurface = BottomBarGestureContainer.Surface.TOOLS
        exitUrlEditing()
        savedAppsShelf.hide()
        bottomToolsShelf.show()
    }

    private fun restoreBottomBarSurface() {
        if (isBrowserFullscreen || isUrlEditing || bottomBarGestureContainer.visibility != View.VISIBLE) return

        when (bottomBarSurface) {
            BottomBarGestureContainer.Surface.APPS -> {
                if (!savedAppsShelf.isVisible || toolbar.visibility != View.GONE) {
                    bottomToolsShelf.hide()
                    savedAppsShelf.show()
                }
            }

            BottomBarGestureContainer.Surface.TOOLS -> {
                if (!bottomToolsShelf.isVisible || toolbar.visibility != View.GONE) {
                    savedAppsShelf.hide()
                    bottomToolsShelf.show()
                }
            }

            BottomBarGestureContainer.Surface.URL -> {
                if (savedAppsShelf.isVisible || bottomToolsShelf.isVisible || toolbar.visibility != View.VISIBLE) {
                    savedAppsShelf.hide()
                    bottomToolsShelf.hide()
                    toolbar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openDownloadsTab() {
        exitUrlEditing()
        ensureDownloadManagerView()
        val store = requireComponents.core.store
        val preferences = requireContext()
            .getSharedPreferences(DOWNLOAD_TAB_PREFERENCES, Context.MODE_PRIVATE)
        val savedId = preferences.getString(DOWNLOAD_TAB_ID, null)
        val selectedDownloadsId = store.state.selectedTab
            ?.takeIf { isDownloadsUrl(it.content.url) }
            ?.id
        val existingId = selectedDownloadsId
            ?: savedId?.takeIf { id -> store.state.tabs.any { it.id == id } }
            ?: store.state.tabs.firstOrNull { isDownloadsUrl(it.content.url) }?.id
        val tabId = existingId ?: requireComponents.useCases.tabsUseCases.addTab(
            url = DOWNLOADS_URL,
            selectTab = true,
        )

        preferences.edit().putString(DOWNLOAD_TAB_ID, tabId).apply()

        downloadTabId = tabId
        store.dispatch(
            mozilla.components.browser.state.action.ContentAction.UpdateTitleAction(
                tabId,
                "Downloads",
            ),
        )
        store.dispatch(
            mozilla.components.browser.state.action.ContentAction.UpdateIconAction(
                tabId,
                DOWNLOADS_URL,
                downloadTabIcon(),
            ),
        )
        requireComponents.useCases.tabsUseCases.selectTab(tabId)
        val currentUrl = store.state.tabs.firstOrNull { it.id == tabId }?.content?.url
        if (!currentUrl.orEmpty().trim().trimEnd('/').equals(DOWNLOADS_URL, ignoreCase = true)) {
            requireComponents.useCases.sessionUseCases.loadUrl.invoke(DOWNLOADS_URL)
        }
        ensureDownloadTabFirst(tabId)
        updateDownloadTabPresentation()
    }

    private fun downloadTabIcon(): Bitmap {
        val drawable = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_download)
            ?: error("Download icon resource is missing")
        val size = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 1)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).also { canvas ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
        }
    }

    private fun isDownloadsUrl(value: String): Boolean {
        val normalized = value.trim().trimEnd('/')
        return normalized.equals(DOWNLOADS_URL, ignoreCase = true) ||
            normalized.equals(LEGACY_DOWNLOADS_URL, ignoreCase = true)
    }

    /** Shows the native panel when a downloads URL was opened/restored directly. */
    private fun restoreDirectDownloadsTabIfNeeded() {
        val selected = requireComponents.core.store.state.selectedTab ?: return
        if (isDownloadsUrl(selected.content.url) &&
            (downloadManagerView == null || downloadTabId != selected.id)
        ) {
            openDownloadsTab()
        }
    }

    private fun ensureDownloadTabFirst(preferredId: String? = downloadTabId) {
        val id = preferredId ?: requireContext()
            .getSharedPreferences(DOWNLOAD_TAB_PREFERENCES, Context.MODE_PRIVATE)
            .getString(DOWNLOAD_TAB_ID, null)
            ?: return
        downloadTabId = id
        val tabs = requireComponents.core.store.state.tabs
        val index = tabs.indexOfFirst { it.id == id }
        if (index > 0) {
            tabs.firstOrNull { it.id != id }?.let { target ->
                requireComponents.useCases.tabsUseCases.moveTabs.invoke(id, target.id, false)
            }
        }
    }

    private fun updateDownloadTabPresentation() {
        val view = downloadManagerView ?: return
        val storedId = requireContext()
            .getSharedPreferences(DOWNLOAD_TAB_PREFERENCES, Context.MODE_PRIVATE)
            .getString(DOWNLOAD_TAB_ID, null)
        if (downloadTabId == null && storedId != null) downloadTabId = storedId
        val selectedId = requireComponents.core.store.state.selectedTab?.id
        view.visibility = if (selectedId != null && selectedId == downloadTabId) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun ensureDownloadManagerView(): DownloadManagerView {
        return downloadManagerView ?: DownloadManagerView(
            context = requireContext(),
            manager = requireComponents.firefoxLiteDownloadManager,
            onDownloadStarted = ::showDownloadShortcut,
        ).also { created ->
            (requireView() as ViewGroup).addView(
                created,
                CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = Gravity.TOP
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
                },
            )
            created.visibility = View.GONE
            downloadManagerView = created
        }
    }

    private fun showDownloadConfirmation(
        download: CurrentDownloadState,
        @Suppress("UNUSED_PARAMETER") duplicate: FileNameOfDuplicateIfAlreadyDownloaded,
        onConfirm: PositiveActionCallback,
        onCancel: NegativeActionCallback,
        @Suppress("UNUSED_PARAMETER") onOpen: OpenFileCallback,
    ) {
        if (!isAdded || view == null) return

        val id = download.value.id
        pendingDownloadConfirmations.remove(id)
        pendingDownloadConfirmations[id] = PendingDownloadConfirmation(
            download = download,
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
        rebuildDownloadConfirmationStack()
    }

    private fun confirmDownload(id: String) {
        val request = pendingDownloadConfirmations.remove(id) ?: return
        rebuildDownloadConfirmationStack()
        // The DownloadsFeature callback starts the native task and consumes the
        // browser request. It must not select or create the Downloads tab.
        showDownloadShortcut()
        request.onConfirm.value(request.download.value)
    }

    private fun cancelDownloadConfirmation(id: String) {
        val request = pendingDownloadConfirmations.remove(id) ?: return
        rebuildDownloadConfirmationStack()
        request.onCancel.value()
    }

    private fun dismissDownloadConfirmations() {
        pendingDownloadConfirmations.clear()
        view?.findViewById<LinearLayout>(R.id.downloadConfirmationStack)?.let { stack ->
            stack.removeAllViews()
            stack.visibility = View.GONE
        }
    }

    private fun rebuildDownloadConfirmationStack() {
        val stack = view?.findViewById<LinearLayout>(R.id.downloadConfirmationStack) ?: return
        stack.removeAllViews()
        // Linked maps retain request order. Inserting newest first keeps the
        // latest confirmation above older requests at the bottom edge.
        pendingDownloadConfirmations.values.toList().asReversed().forEach { request ->
            stack.addView(createDownloadConfirmationCard(request))
        }
        stack.visibility = if (pendingDownloadConfirmations.isEmpty()) View.GONE else View.VISIBLE
        if (pendingDownloadConfirmations.isNotEmpty()) stack.bringToFront()
    }

    private fun createDownloadConfirmationCard(
        request: PendingDownloadConfirmation,
    ): View {
        val id = request.download.value.id
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(2), 0)
            background = GradientDrawable().apply {
                setColor(0xFF252B31.toInt())
                cornerRadius = dp(9).toFloat()
            }
            elevation = dp(4).toFloat()
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                minOf(dp(420), resources.displayMetrics.widthPixels - dp(16)),
                dp(44),
            ).apply {
                topMargin = dp(4)
            }
        }

        val downloadState = request.download.value
        val fileName = TextView(requireContext()).apply {
            text = requireComponents.firefoxLiteDownloadManager.resolveSuggestedFileName(
                // A redirect often changes /content.xx into the real
                // filename-bearing URL. Prefer the final response URL when
                // it is available for the confirmation card.
                url = downloadState.response?.url?.takeIf { it.isNotBlank() } ?: downloadState.url,
                suggestedFileName = downloadState.fileName,
                contentDisposition = downloadHeader(downloadState, "Content-Disposition"),
                contentLocation = downloadHeader(downloadState, "Content-Location"),
                contentType = downloadHeader(downloadState, "Content-Type"),
            )
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = null
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        card.addView(fileName)
        card.addView(
            createDownloadConfirmationButton(
                icon = R.drawable.ic_download,
                description = "Confirm download",
                action = { confirmDownload(id) },
            ),
        )
        card.addView(
            createDownloadConfirmationButton(
                icon = android.R.drawable.ic_menu_close_clear_cancel,
                description = "Cancel download",
                action = { cancelDownloadConfirmation(id) },
            ),
        )
        return card
    }

    private fun downloadHeader(
        download: mozilla.components.browser.state.state.content.DownloadState,
        name: String,
    ): String? {
        val headers = download.response?.headers ?: return null
        return headers.getAll(name).firstOrNull { it.isNotBlank() }
            ?: headers.get(name)?.takeIf { it.isNotBlank() }
    }

    private fun createDownloadConfirmationButton(
        icon: Int,
        description: String,
        action: () -> Unit,
    ): ImageButton = ImageButton(requireContext()).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        contentDescription = description
        background = null
        setPadding(dp(9), dp(9), dp(9), dp(9))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { action() }
    }

    private fun showDownloadShortcut() {
        downloadShortcutVisible = true
        downloadShortcutJob?.cancel()
        downloadShortcutAnimator?.cancel()
        downloadShortcutAnimator = ValueAnimator.ofArgb(
            DOWNLOAD_ICON_BLUE,
            DOWNLOAD_ICON_DARK,
            DOWNLOAD_ICON_BLUE,
        ).apply {
            duration = DOWNLOAD_ICON_COLOR_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                if (view != null) {
                    downloadShortcutButton.imageTintList = ColorStateList.valueOf(
                        animator.animatedValue as Int,
                    )
                }
            }
            start()
        }
        downloadShortcutJob = lifecycleScope.launch {
            delay(DOWNLOAD_SHORTCUT_DURATION_MS)
            hideDownloadShortcut()
        }
        view?.post {
            if (isAdded) updateDownloadShortcutButton()
        }
    }

    private fun hideDownloadShortcut() {
        downloadShortcutVisible = false
        downloadShortcutJob?.cancel()
        downloadShortcutJob = null
        downloadShortcutAnimator?.cancel()
        downloadShortcutAnimator = null
        if (view != null) {
            downloadShortcutButton.imageTintList = ColorStateList.valueOf(DOWNLOAD_ICON_BLUE)
            updateDownloadShortcutButton()
        }
    }

    private fun updateDownloadShortcutButton() {
        if (view == null) return
        downloadShortcutButton.visibility = if (downloadShortcutVisible) View.VISIBLE else View.GONE
        if (downloadShortcutVisible) downloadShortcutButton.bringToFront()
        // The old URL-editor action overlay is intentionally kept hidden; the
        // short-lived shortcut now lives at the top center of the screen.
        urlInputActionOverlay.visibility = View.GONE
    }

    private fun showRefreshSubmenu(anchor: View) {
        val menu = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFF242424.toInt())
        }
        val popup = PopupWindow(
            menu,
            dp(190),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF242424.toInt()))
        }

        fun addItem(label: String, action: () -> Unit) {
            menu.addView(
                TextView(requireContext()).apply {
                    text = label
                    textSize = 15f
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48),
                    )
                    setOnClickListener {
                        popup.dismiss()
                        action()
                    }
                },
            )
        }

        addItem("Refresh") { requireComponents.useCases.sessionUseCases.reload.invoke() }
        addItem("Clear site data") { clearCurrentSiteData() }
        popup.showAsDropDown(anchor, 0, -anchor.height - dp(112))
    }

    private fun clearCurrentSiteData() {
        val tab = requireComponents.core.store.state.selectedTab ?: return
        val host = Uri.parse(tab.content.url).host
        if (host.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No site data to clear", Toast.LENGTH_SHORT).show()
            return
        }

        val engineSession = tab.engineState.engineSession ?: return
        engineSession.clearData(
            Engine.BrowsingData.allSiteData(),
            host,
            {
                requireComponents.useCases.sessionUseCases.reload.invoke()
                Toast.makeText(requireContext(), "Cleared site data for $host", Toast.LENGTH_SHORT).show()
            },
            { error ->
                Toast.makeText(
                    requireContext(),
                    "Could not clear site data: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    private fun openDeveloperTools() {
        val session = requireComponents.core.store.state.selectedTab?.engineState?.engineSession
        if (session == null) {
            Toast.makeText(requireContext(), "No active page", Toast.LENGTH_SHORT).show()
            return
        }

        val source = resources.openRawResource(R.raw.firefoxlite_devtools)
            .bufferedReader()
            .use { it.readText() }
        val javascriptUrl = "javascript:(function(){${source}\n})();"
        try {
            session.loadUrl(
                javascriptUrl,
                flags = EngineSession.LoadUrlFlags.select(EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL),
            )
        } catch (error: Throwable) {
            Log.e("FirefoxLite", "Unable to open in-page developer tools", error)
            Toast.makeText(requireContext(), "Developer tools are unavailable on this page", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun changePageScale(delta: Float) {
        pageScale = ((pageScale + delta).coerceIn(MIN_ZOOM, MAX_ZOOM) * 20f)
            .roundToInt() / 20f
        applyWebContentZoom()
        showZoomLevel()
    }

    private fun showZoomLevel() {
        zoomLevelIndicator.text = "${(pageScale * 100f).roundToInt()}%"
        zoomLevelIndicator.visibility = View.VISIBLE
        zoomIndicatorJob?.cancel()
        zoomIndicatorJob = lifecycleScope.launch {
            delay(ZOOM_INDICATOR_DURATION_MS)
            if (isAdded) zoomLevelIndicator.visibility = View.GONE
        }
    }

    private fun applyWebContentZoom() {
        val session = requireComponents.core.store.state.selectedTab?.engineState?.engineSession ?: return
        val zoom = String.format(Locale.US, "%.2f", pageScale)
        val javascriptUrl = "javascript:(function(){var root=document.documentElement;if(root){root.style.setProperty('zoom','$zoom');root.dataset.firefoxliteZoom='$zoom';}})();"
        try {
            session.loadUrl(
                javascriptUrl,
                flags = EngineSession.LoadUrlFlags.select(EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL),
            )
        } catch (error: Throwable) {
            Log.e("FirefoxLite", "Unable to apply web-content zoom", error)
        }
    }

    private fun showFullscreenExitButton() {
        if (!isBrowserFullscreen) return

        fullscreenExitButton.visibility = View.VISIBLE
        fullscreenExitJob?.cancel()
        fullscreenExitJob = lifecycleScope.launch {
            delay(3_000L)
            if (isBrowserFullscreen) fullscreenExitButton.visibility = View.GONE
        }
    }

    private fun showTabs() {
        exitUrlEditing()
        val fragmentManager = activity?.supportFragmentManager ?: return
        if (fragmentManager.findFragmentByTag(TABS_TRAY_TAG) != null) return

        // Keep the browser alive underneath the tray so the tab switcher can be
        // a lightweight bottom panel instead of rebuilding the browser screen.
        fragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .add(R.id.container, TabsTrayFragment(), TABS_TRAY_TAG)
            .commit()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun deleteHistorySuggestion(suggestion: Suggestion) {
        lifecycleScope.launch(Dispatchers.IO) {
            suggestion.description?.let {
                requireComponents.core.historyStorage.deleteHistoryMetadataForUrl(it)
            }
        }
    }

    override fun onBackPressed(): Boolean {
        if (isBrowserFullscreen) {
            exitBrowserFullscreen()
            return true
        }

        if (isUrlEditing || urlEditText.hasFocus()) {
            exitUrlEditing()
            return true
        }

        return readerViewFeature.onBackPressed() || super.onBackPressed()
    }

    companion object {
        private const val TABS_TRAY_TAG = "firefoxlite_tabs_tray"
        private const val ZOOM_STEP = 0.05f
        private const val MIN_ZOOM = 0.70f
        private const val MAX_ZOOM = 2.00f
        private const val ZOOM_INDICATOR_DURATION_MS = 3_000L
        private const val URL_PREVIEW_DELAY_MS = 2_000L
        private const val URL_PREVIEW_REOPEN_DELAY_MS = 150L
        private const val URL_PREVIEW_SIZE_DP = 400
        private const val URL_PREVIEW_SCREEN_MARGIN_DP = 8
        private const val URL_PREVIEW_HEADER_DP = 36
        private const val URL_EDITOR_ACTIVATION_GRACE_MS = 450L
        private const val URL_EDITOR_FOCUS_LOSS_GRACE_MS = 120L
        private const val TAB_COUNTER_INSTALL_ATTEMPTS = 10
        private const val TAB_COUNTER_INSTALL_DELAY_MS = 100L
        private const val ABOUT_BLANK_URL = "about:blank"
        private const val DOWNLOADS_URL = "about:downloads"
        private const val LEGACY_DOWNLOADS_URL = "about:download"
        private const val DOWNLOAD_TAB_PREFERENCES = "firefoxlite_download_tab"
        private const val DOWNLOAD_TAB_ID = "tab_id"
        private const val DOWNLOAD_SHORTCUT_DURATION_MS = 5_000L
        private const val DOWNLOAD_ICON_COLOR_CYCLE_MS = 900L
        private val DOWNLOAD_ICON_BLUE = Color.rgb(33, 150, 243)
        private val DOWNLOAD_ICON_DARK = Color.rgb(38, 50, 56)

        fun create(sessionId: String? = null) =
            BrowserFragment().apply {
            arguments = Bundle().apply {
                putSessionId(sessionId)
            }
        }
    }
}
