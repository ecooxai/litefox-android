/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import androidx.compose.ui.platform.ComposeView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch
import mozilla.components.support.ktx.kotlin.isSameOriginAs
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.compose.browser.toolbar.BrowserToolbar
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.downloads.CurrentDownloadState
import mozilla.components.feature.downloads.FileNameOfDuplicateIfAlreadyDownloaded
import mozilla.components.feature.downloads.NegativeActionCallback
import mozilla.components.feature.downloads.OpenFileCallback
import mozilla.components.feature.downloads.PositiveActionCallback
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.temporary.ShareResourceFeature
import mozilla.components.feature.findinpage.view.FindInPageBar
import mozilla.components.feature.findinpage.view.FindInPageView
import mozilla.components.feature.media.fullscreen.MediaSessionFullscreenFeature
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.session.FullScreenFeature
import mozilla.components.feature.session.ScreenOrientationFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.feature.webauthn.WebAuthnFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.view.enterImmersiveMode
import mozilla.components.support.ktx.android.view.exitImmersiveMode
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.reference.browser.BuildConfig
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.addons.WebExtensionPromptFeature
import org.mozilla.reference.browser.downloads.FirefoxLiteDownloadManagerAdapter
import org.mozilla.reference.browser.ext.getPreferenceKey
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.pip.PictureInPictureIntegration
import org.mozilla.reference.browser.settings.Settings
import org.mozilla.reference.browser.tabs.LastTabFeature

private const val IME_WINDOW_INSETS_LISTENER_KEY = "firefox-lite-ime-viewport"

internal fun resolveSitePermissionGrantResults(
    permissions: Array<String>,
    activityResults: Map<String, Boolean>,
    isCurrentlyGranted: (String) -> Boolean,
): IntArray = permissions.map { permission ->
    if (activityResults[permission] == true || isCurrentlyGranted(permission)) {
        PackageManager.PERMISSION_GRANTED
    } else {
        PackageManager.PERMISSION_DENIED
    }
}.toIntArray()

/**
 * Base fragment extended by [BrowserFragment] and [ExternalAppBrowserFragment].
 * This class only contains shared code focused on the main browsing content.
 * UI code specific to the app or to custom tabs can be found in the subclasses.
 */
abstract class BaseBrowserFragment :
    Fragment(),
    UserInteractionHandler,
    ActivityResultHandler {
    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val toolbarIntegration = ViewBoundFeatureWrapper<ToolbarIntegration>()
    private val contextMenuIntegration = ViewBoundFeatureWrapper<ContextMenuIntegration>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val shareResourceFeature = ViewBoundFeatureWrapper<ShareResourceFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private val promptsFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val webExtensionPromptFeature = ViewBoundFeatureWrapper<WebExtensionPromptFeature>()
    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()
    private val findInPageIntegration = ViewBoundFeatureWrapper<FindInPageIntegration>()
    private val sitePermissionFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    private val pictureInPictureIntegration = ViewBoundFeatureWrapper<PictureInPictureIntegration>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()
    private val webAuthnFeature = ViewBoundFeatureWrapper<WebAuthnFeature>()
    private val fullScreenMediaSessionFeature = ViewBoundFeatureWrapper<MediaSessionFullscreenFeature>()
    private val lastTabFeature = ViewBoundFeatureWrapper<LastTabFeature>()
    private val screenOrientationFeature = ViewBoundFeatureWrapper<ScreenOrientationFeature>()
    private var webFullscreenEnabled = false

    private val engineView: EngineView
        get() = requireView().findViewById<View>(R.id.engineView) as EngineView
    private val toolbar: BrowserToolbar
        get() = requireView().findViewById(R.id.toolbar)
    private val bottomBarGestureContainer: BottomBarGestureContainer
        get() = requireView().findViewById(R.id.bottomBarGestureContainer)
    private val findInPageBar: FindInPageBar
        get() = requireView().findViewById(R.id.findInPageBar)
    private val swipeRefresh: SwipeRefreshLayout
        get() = requireView().findViewById(R.id.swipeRefresh)

    private val backButtonHandler: List<ViewBoundFeatureWrapper<*>> = listOf(
        fullScreenFeature,
        findInPageIntegration,
        toolbarIntegration,
        sessionFeature,
        lastTabFeature,
    )

    private val activityResultHandler: List<ViewBoundFeatureWrapper<*>> = listOf(
        webAuthnFeature,
        promptsFeature,
    )

    protected val sessionId: String?
        get() = arguments?.getString(SESSION_ID)

    protected var webAppToolbarShouldBeVisible = true

    private lateinit var requestDownloadPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestSitePermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestPromptsPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private var pendingSitePermissions: Array<String> = emptyArray()

    private var appsShelfOpener: (() -> Unit)? = null
    private var extensionOpener: ((String) -> Unit)? = null
    private var pinnedExtensionsRefresher: (() -> Unit)? = null
    private var fullscreenOpener: (() -> Unit)? = null
    private var downloadsOpener: (() -> Unit)? = null
    private var downloadConfirmationHandler: (
        (
            CurrentDownloadState,
            FileNameOfDuplicateIfAlreadyDownloaded,
            PositiveActionCallback,
            NegativeActionCallback,
            OpenFileCallback,
        ) -> Unit
    )? = null
    private var downloadConfirmationDismissHandler: (() -> Unit)? = null
    private var downloadStartedHandler: (() -> Unit)? = null
    private var bottomBarWasVisibleDuringWebFullscreen = false
    private var viewportLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    protected open val supportsAppsShelf: Boolean = false

    protected fun setAppsShelfOpener(opener: () -> Unit) {
        appsShelfOpener = opener
    }

    protected fun setExtensionOpener(opener: (String) -> Unit) {
        extensionOpener = opener
    }

    protected fun setPinnedExtensionsRefresher(refresher: () -> Unit) {
        pinnedExtensionsRefresher = refresher
    }

    protected fun setFullscreenOpener(opener: () -> Unit) {
        fullscreenOpener = opener
    }

    protected fun setDownloadsOpener(opener: () -> Unit) {
        downloadsOpener = opener
    }

    protected fun setDownloadConfirmationHandler(
        handler: (
            CurrentDownloadState,
            FileNameOfDuplicateIfAlreadyDownloaded,
            PositiveActionCallback,
            NegativeActionCallback,
            OpenFileCallback,
        ) -> Unit,
    ) {
        downloadConfirmationHandler = handler
    }

    protected fun setDownloadConfirmationDismissHandler(handler: () -> Unit) {
        downloadConfirmationDismissHandler = handler
    }

    protected fun setDownloadStartedHandler(handler: () -> Unit) {
        downloadStartedHandler = handler
    }

    private fun openAppsShelf() {
        appsShelfOpener?.invoke()
    }

    override fun onResume() {
        super.onResume()
        swipeRefresh.isEnabled = Settings.isPullToRefreshEnabled(requireContext())
        applyNativeForceZoom()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestDownloadPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val permissions = results.keys.toTypedArray()
                val grantResults =
                    results.values
                        .map {
                        if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                    }.toIntArray()
                downloadsFeature.withFeature {
                    it.onPermissionsResult(permissions, grantResults)
                }
            }

        requestSitePermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                // RequestMultiplePermissions returns a map, so do not use the
                // map iteration order when pairing permissions and results.
                // SitePermissionsFeature keeps the pending Gecko permission
                // request in the same order in which it was delivered.
                val permissions = pendingSitePermissions
                    .takeIf { it.isNotEmpty() }
                    ?: results.keys.toTypedArray()
                val currentContext = context
                val grantResults = resolveSitePermissionGrantResults(permissions, results) { permission ->
                    currentContext != null &&
                        ContextCompat.checkSelfPermission(currentContext, permission) ==
                        PackageManager.PERMISSION_GRANTED
                }
                pendingSitePermissions = emptyArray()
                sitePermissionFeature.withFeature {
                    it.onPermissionsResult(permissions, grantResults)
                }
            }

        requestPromptsPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val permissions = results.keys.toTypedArray()
                val grantResults =
                    results.values
                        .map {
                        if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                    }.toIntArray()
                promptsFeature.withFeature {
                    it.onPermissionsResult(permissions, grantResults)
                }
            }
    }

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_browser, container, false)

    abstract val shouldUseComposeUI: Boolean

    @CallSuper
    @Suppress("LongMethod")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        sessionFeature.set(
            feature = SessionFeature(
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases.goBack,
                requireComponents.useCases.sessionUseCases.goForward,
                engineView,
                sessionId,
            ),
            owner = this,
            view = view,
        )

        // A fixed toolbar must reserve layout space, not compositor clipping.
        (bottomBarGestureContainer.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior = null
        engineView.setDynamicToolbarMaxHeight(0)
        engineView.setVerticalClipping(0)

        toolbarIntegration.set(
            feature = ToolbarIntegration(
                requireContext(),
                toolbar,
                requireComponents.core.historyStorage,
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases,
                requireComponents.useCases.tabsUseCases,
                requireComponents.useCases.webAppUseCases,
                showApps = if (supportsAppsShelf) ::openAppsShelf else null,
                showDownloads = if (supportsAppsShelf) downloadsOpener else null,
                onPinnedExtensionsChanged = pinnedExtensionsRefresher ?: {},
                onExtensionClicked = extensionOpener ?: {},
                onFullscreenClicked = fullscreenOpener ?: {},
                sessionId = sessionId,
            ),
            owner = this,
            view = view,
        )

        contextMenuIntegration.set(
            feature = ContextMenuIntegration(
                requireContext(),
                parentFragmentManager,
                requireComponents.core.store,
                requireComponents.useCases.tabsUseCases,
                requireComponents.useCases.contextMenuUseCases,
                engineView,
                view,
                sessionId,
            ),
            owner = this,
            view = view,
        )
        shareResourceFeature.set(
            ShareResourceFeature(
                context = requireContext().applicationContext,
                httpClient = requireComponents.core.client,
                store = requireComponents.core.store,
                tabId = sessionId,
            ),
            owner = this,
            view = view,
        )

        downloadsFeature.set(
            feature = DownloadsFeature(
                requireContext(),
                store = requireComponents.core.store,
                useCases = requireComponents.useCases.downloadsUseCases,
                fragmentManager = childFragmentManager,
                downloadFileUtils = DefaultDownloadFileUtils(
                    context = requireContext().applicationContext,
                    downloadLocation = {
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                    },
                ),
                downloadManager = FirefoxLiteDownloadManagerAdapter(
                    requireComponents.firefoxLiteDownloadManager,
                ),
                onNeedToRequestPermissions = { permissions ->
                    requestDownloadPermissionsLauncher.launch(permissions)
                },
                customFirstPartyDownloadDialog = downloadConfirmationHandler,
                onDownloadStartedListener = {
                    downloadStartedHandler?.invoke() ?: downloadsOpener?.invoke()
                },
            ),
            owner = this,
            view = view,
        )

        appLinksFeature.set(
            feature = AppLinksFeature(
                requireContext(),
                store = requireComponents.core.store,
                sessionId = sessionId,
                fragmentManager = parentFragmentManager,
                launchInApp = {
                    prefs.getBoolean(requireContext().getPreferenceKey(R.string.pref_key_launch_external_app), false)
                },
            ),
            owner = this,
            view = view,
        )

        promptsFeature.set(
            feature = PromptFeature(
                fragment = this,
                store = requireComponents.core.store,
                tabsUseCases = requireComponents.useCases.tabsUseCases,
                customTabId = sessionId,
                fileUploadsDirCleaner = requireComponents.core.fileUploadsDirCleaner,
                fragmentManager = parentFragmentManager,
                onNeedToRequestPermissions = { permissions ->
                    requestPromptsPermissionsLauncher.launch(permissions)
                },
            ),
            owner = this,
            view = view,
        )

        webExtensionPromptFeature.set(
            feature = WebExtensionPromptFeature(
                store = requireComponents.core.store,
                context = requireContext(),
                fragmentManager = parentFragmentManager,
            ),
            owner = this,
            view = view,
        )

        windowFeature.set(
            feature = WindowFeature(requireComponents.core.store, requireComponents.useCases.tabsUseCases),
            owner = this,
            view = view,
        )

        fullScreenFeature.set(
            feature = FullScreenFeature(
                store = requireComponents.core.store,
                sessionUseCases = requireComponents.useCases.sessionUseCases,
                tabId = sessionId,
                viewportFitChanged = ::viewportFitChanged,
                fullScreenChanged = ::fullScreenChanged,
            ),
            owner = this,
            view = view,
        )

        findInPageIntegration.set(
            feature = FindInPageIntegration(
                requireComponents.core.store,
                sessionId,
                findInPageBar as FindInPageView,
                engineView,
            ),
            owner = this,
            view = view,
        )

        sitePermissionFeature.set(
            feature = SitePermissionsFeature(
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                sessionId = sessionId,
                storage = requireComponents.core.geckoSitePermissionsStorage,
                onNeedToRequestPermissions = { permissions ->
                    val requestedPermissions = permissions.distinct().toTypedArray()
                    pendingSitePermissions = requestedPermissions

                    // Ask Android only for permissions that are actually
                    // missing. Camera and microphone can have different grant
                    // states, and Gecko must receive the result for both in
                    // the original request order.
                    val missingPermissions = requestedPermissions.filter { permission ->
                        ContextCompat.checkSelfPermission(requireContext(), permission) !=
                            PackageManager.PERMISSION_GRANTED
                    }

                    if (missingPermissions.isEmpty()) {
                        val grantResults = requestedPermissions.map {
                            PackageManager.PERMISSION_GRANTED
                        }.toIntArray()
                        pendingSitePermissions = emptyArray()
                        sitePermissionFeature.withFeature {
                            it.onPermissionsResult(requestedPermissions, grantResults)
                        }
                    } else {
                        requestSitePermissionsLauncher.launch(missingPermissions.toTypedArray())
                    }
                },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                store = requireComponents.core.store,
            ),
            owner = this,
            view = view,
        )

        pictureInPictureIntegration.set(
            feature = PictureInPictureIntegration(
                requireComponents.core.store,
                requireActivity(),
                sessionId,
            ),
            owner = this,
            view = view,
        )

        fullScreenMediaSessionFeature.set(
            feature = MediaSessionFullscreenFeature(
                requireActivity(),
                requireComponents.core.store,
                sessionId,
            ),
            owner = this,
            view = view,
        )

        installImeViewportClipping(view)
        swipeRefreshFeature.set(
            feature = SwipeRefreshFeature(
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases.reload,
                swipeRefresh,
            ),
            owner = this,
            view = view,
        )

        lastTabFeature.set(
            feature = LastTabFeature(
                requireComponents.core.store,
                sessionId,
                requireComponents.useCases.tabsUseCases.removeTab,
                requireActivity(),
            ),
            owner = this,
            view = view,
        )

        screenOrientationFeature.set(
            feature = ScreenOrientationFeature(
                requireComponents.core.engine,
                requireActivity(),
            ),
            owner = this,
            view = view,
        )

        if (BuildConfig.MOZILLA_OFFICIAL) {
            webAuthnFeature.set(
                feature = WebAuthnFeature(
                    requireComponents.core.engine,
                    requireActivity(),
                    requireComponents.useCases.sessionUseCases.exitFullscreen::invoke,
                ) { requireComponents.core.store.state.selectedTabId },
                owner = this,
                view = view,
            )
        }

        val composeView = view.findViewById<ComposeView>(R.id.compose_view)
        if (shouldUseComposeUI) {
            composeView.visibility = View.VISIBLE
            composeView.setContent { BrowserToolbar() }

            val params = swipeRefresh.layoutParams as CoordinatorLayout.LayoutParams
            params.topMargin = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
            swipeRefresh.layoutParams = params
        }

        applyNativeForceZoom()
        installDownloadPromptLifecycle()
    }

    /** AC 154 lacks the custom-dialog dismissal callback; keep that lifecycle here. */
    private fun installDownloadPromptLifecycle() {
        viewLifecycleOwner.lifecycleScope.launch {
            var previousTabId: String? = null
            var previousUrl: String? = null
            requireComponents.core.store.flow().collect { state ->
                val tab = state.findCustomTabOrSelectedTab(sessionId)
                val oldUrl = previousUrl
                val tabChanged = previousTabId != null && previousTabId != tab?.id
                val originChanged = oldUrl != null && tab != null &&
                    !oldUrl.isSameOriginAs(tab.content.url)
                previousTabId = tab?.id
                previousUrl = tab?.content?.url
                if (tabChanged || originChanged) {
                    downloadConfirmationDismissHandler?.invoke()
                }
            }
        }
    }

    private fun applyNativeForceZoom() {
        requireComponents.core.engine.settings.forceUserScalableContent =
            Settings.isForceZoomEnabled(requireContext())
    }

    override fun onDestroyView() {
        downloadConfirmationDismissHandler?.invoke()
        pendingSitePermissions = emptyArray()
        runCatching { engineView.removeWindowInsetsListener(IME_WINDOW_INSETS_LISTENER_KEY) }
        viewportLayoutListener?.let { listener ->
            view?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        viewportLayoutListener = null
        view?.let { ViewCompat.setWindowInsetsAnimationCallback(it, null) }
        webFullscreenEnabled = false
        super.onDestroyView()
    }

    private fun fullScreenChanged(enabled: Boolean) {
        webFullscreenEnabled = enabled
        if (enabled) {
            bottomBarWasVisibleDuringWebFullscreen =
                view?.findViewById<View>(R.id.bottomBarGestureContainer)?.visibility == View.VISIBLE
            activity?.enterImmersiveMode()
            view?.findViewById<View>(R.id.bottomBarGestureContainer)?.visibility = View.GONE
            engineView.setDynamicToolbarMaxHeight(0)
        } else {
            activity?.exitImmersiveMode()
            if (bottomBarWasVisibleDuringWebFullscreen) {
                view?.findViewById<View>(R.id.bottomBarGestureContainer)?.visibility = View.VISIBLE
            } else {
                toolbar.visibility = View.VISIBLE
            }
            bottomBarWasVisibleDuringWebFullscreen = false
            engineView.setDynamicToolbarMaxHeight(0)
        }
    }

    /** Resize the engine surface so CSS viewport units and fixed controls match visible pixels. */
    private fun installImeViewportClipping(root: View) {
        val bar = root.findViewById<View>(R.id.bottomBarGestureContainer)
        val browser = root.findViewById<View>(R.id.swipeRefresh)
        var hiddenForPageKeyboard = false
        var lastInsets: WindowInsetsCompat? = null

        fun update(insets: WindowInsetsCompat) {
            lastInsets = insets
            val window = activity?.window ?: return
            WindowViewport.applyInsets(root, window, insets)
            val pageKeyboard = insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0 && !toolbar.hasFocus()
            if (pageKeyboard && bar.visibility == View.VISIBLE && !webFullscreenEnabled) {
                hiddenForPageKeyboard = true
                bar.visibility = View.INVISIBLE
            } else if (!pageKeyboard && hiddenForPageKeyboard) {
                if (bar.visibility == View.INVISIBLE && !webFullscreenEnabled) bar.visibility = View.VISIBLE
                hiddenForPageKeyboard = false
            }
            // Translation changes only what is drawn; real margins change Gecko's viewport.
            if (bar.translationY != 0f) bar.translationY = 0f
            val barHeight = bar.height.takeIf { it > 0 }
                ?: resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
            val bottomMargin = ViewportGeometry.contentBottomMargin(
                barHeight, bar.visibility == View.VISIBLE, pageKeyboard, webFullscreenEnabled,
            )
            val params = browser.layoutParams as ViewGroup.MarginLayoutParams
            if (params.bottomMargin != bottomMargin) {
                params.bottomMargin = bottomMargin
                browser.layoutParams = params
            }
        }

        engineView.addWindowInsetsListener(IME_WINDOW_INSETS_LISTENER_KEY) { _, insets ->
            update(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    update(insets)
                    return insets
                }
            },
        )
        viewportLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            (lastInsets ?: ViewCompat.getRootWindowInsets(root))?.let(::update)
        }.also { root.viewTreeObserver.addOnGlobalLayoutListener(it) }
        root.post { ViewCompat.getRootWindowInsets(root)?.let(::update) }
        ViewCompat.requestApplyInsets(root)
    }

    private fun viewportFitChanged(viewportFit: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireActivity().window.attributes.layoutInDisplayCutoutMode = viewportFit
        }
    }

    @CallSuper
    override fun onBackPressed(): Boolean = backButtonHandler.any { it.onBackPressed() }

    final override fun onHomePressed(): Boolean = pictureInPictureIntegration.get()?.onHomePressed() ?: false

    final override fun onPictureInPictureModeChanged(enabled: Boolean) {
        val session = requireComponents.core.store.state.selectedTab
        val fullScreenMode = session?.content?.fullScreen ?: false
        // If we're exiting PIP mode and we're in fullscreen mode, then we should exit fullscreen mode as well.
        if (!enabled && fullScreenMode) {
            onBackPressed()
            fullScreenChanged(false)
        }
    }

    companion object {
        private const val SESSION_ID = "session_id"

        @JvmStatic
        protected fun Bundle.putSessionId(sessionId: String?) {
            putString(SESSION_ID, sessionId)
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        data: Intent?,
        resultCode: Int,
    ): Boolean {
        Logger.info(
            "Fragment onActivityResult received with " +
                "requestCode: $requestCode, resultCode: $resultCode, data: $data",
        )

        return activityResultHandler.any { it.onActivityResult(requestCode, data, resultCode) }
    }

    /**
     * AndroidX Fragment delivers results from [Fragment.startActivityForResult] directly to this
     * callback. Bridge that callback to [ActivityResultHandler] so file-picker results reach
     * [PromptFeature] instead of being dropped before the Activity can forward them.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        onActivityResult(requestCode, data, resultCode)
    }
}
