package org.mozilla.reference.browser.tabs

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.tabstray.DefaultTabViewHolder
import mozilla.components.browser.tabstray.TabsAdapter
import mozilla.components.browser.tabstray.TabsTray
import mozilla.components.browser.tabstray.TabsTrayStyling
import mozilla.components.browser.tabstray.ViewHolderProvider
import mozilla.components.browser.thumbnails.loader.ThumbnailLoader
import mozilla.components.feature.tabs.tabstray.TabsFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.UserInteractionHandler
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.browser.ViewportGeometry
import org.mozilla.reference.browser.browser.WindowViewport
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.requireComponents

/** Header-free tab panel, with preview/icon layouts and toolbar-only swipe dismissal. */
class TabsTrayFragment : Fragment(), UserInteractionHandler {
    private var tabsFeature: TabsFeature? = null
    private var tabsAdapter: TabsAdapter? = null
    private var iconAdapter: TabIconAdapter? = null
    private var showingIcons = false
    private var showingPrivate = false
    private var closing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_tabstray, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        closing = false
        val state = requireComponents.core.store.state
        showingIcons = savedInstanceState?.getBoolean("iconView") ?: false
        showingPrivate = savedInstanceState?.getBoolean("privateView")
            ?: (state.tabs.firstOrNull { it.id == state.selectedTabId }?.content?.private == true)
        val adapter = createAndSetupTabsTray(requireContext())
        tabsAdapter = adapter
        tabsFeature = TabsFeature(adapter, requireComponents.core.store, ::closeTabsTray) {
            it.content.private == showingPrivate
        }
        iconAdapter = TabIconAdapter(
            onSelect = { tab ->
                requireComponents.useCases.tabsUseCases.selectTab(tab.id)
                closeTabsTray()
            },
            onClose = { tab -> requireComponents.useCases.tabsUseCases.removeTab(tab.id) },
        )
        view.findViewById<RecyclerView>(R.id.allTabsTray).apply {
            layoutManager = GridLayoutManager(requireContext(), 1)
            this.adapter = iconAdapter
        }
        view.findViewById<TabsToolbar>(R.id.tabsToolbar).apply {
            initialize(tabsFeature) { closeTabsTray() }
            onToggleIconView = {
                showingIcons = !showingIcons
                refreshViews()
            }
            onTogglePrivateTabs = {
                showingPrivate = !showingPrivate
                tabsFeature?.filterTabs { it.content.private == showingPrivate }
                refreshViews()
            }
        }
        (view as TabsTrayGestureContainer).onSwipeDownFromBottomBar = ::closeTabsTray
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            WindowViewport.applyInsets(root, requireActivity().window, insets)
            root.post { if (this.view === root) updateTabsTrayHeight(root, adapter) }
            insets
        }
        view.addOnLayoutChangeListener { root, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) {
                root.post { if (this.view === root) updateTabsTrayHeight(root, adapter) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireComponents.core.store.flow().collect { refreshViews() }
            }
        }
        refreshViews()
        ViewCompat.requestApplyInsets(view)
    }

    private fun refreshViews() {
        val root = view ?: return
        val state = requireComponents.core.store.state
        val tabs = state.tabs.filter { it.content.private == showingPrivate }
        iconAdapter?.submitTabs(tabs, state.selectedTabId)
        root.findViewById<View>(R.id.tabsTray).visibility =
            if (!showingIcons && tabs.isNotEmpty()) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.allTabsTray).visibility =
            if (showingIcons && tabs.isNotEmpty()) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.emptyTabsMessage).visibility =
            if (tabs.isEmpty()) View.VISIBLE else View.GONE
        root.findViewById<TabsToolbar>(R.id.tabsToolbar).apply {
            updateToolbar(showingPrivate)
            showIconView(showingIcons)
        }
        root.post { if (view === root) tabsAdapter?.let { updateTabsTrayHeight(root, it) } }
    }

    override fun onStart() { super.onStart(); tabsFeature?.start() }
    override fun onStop() { tabsFeature?.stop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("iconView", showingIcons)
        outState.putBoolean("privateView", showingPrivate)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.tabsTray)?.adapter = null
        view?.findViewById<RecyclerView>(R.id.allTabsTray)?.adapter = null
        tabsFeature = null
        tabsAdapter = null
        iconAdapter = null
        super.onDestroyView()
    }
    override fun onBackPressed(): Boolean { closeTabsTray(); return true }
    private fun closeTabsTray() {
        if (!isAdded || closing || parentFragmentManager.isStateSaved) return
        closing = true
        parentFragmentManager.beginTransaction().setReorderingAllowed(true).remove(this).commit()
    }

    private fun updateTabsTrayHeight(root: View, adapter: RecyclerView.Adapter<*>) {
        if (root.height <= 0 || view !== root) return
        val density = root.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = root.findViewById<View>(R.id.tabsTrayPanel)
        val panelMargins = panel.layoutParams as ViewGroup.MarginLayoutParams
        val available = (root.height - root.paddingTop - root.paddingBottom - panelMargins.bottomMargin)
            .coerceAtLeast(0)
        val footer = minOf(dp(56), available)
        val maxPanel = (available * 0.6f).toInt().coerceIn(footer, available)
        val width = (root.width - root.paddingLeft - root.paddingRight - dp(16)).coerceAtLeast(1)
        val previews = root.findViewById<RecyclerView>(R.id.tabsTray)
        val icons = root.findViewById<RecyclerView>(R.id.allTabsTray)
        val previewSpans = (width / dp(158).coerceAtLeast(1)).coerceAtLeast(1)
        val iconSpans = (width / dp(72).coerceAtLeast(1)).coerceAtLeast(1)
        (previews.layoutManager as GridLayoutManager).spanCount = previewSpans
        (icons.layoutManager as GridLayoutManager).spanCount = iconSpans
        val count = if (showingIcons) {
            requireComponents.core.store.state.tabs.count { it.content.private == showingPrivate }
        } else {
            adapter.itemCount
        }
        val spans = if (showingIcons) iconSpans else previewSpans
        val rows = ((count + spans - 1) / spans).coerceAtLeast(1)
        val listHeight = ViewportGeometry.listHeight(maxPanel, footer, rows, dp(if (showingIcons) 80 else 112))
        setViewHeight(root.findViewById(R.id.tabsToolbar), footer)
        setViewHeight(root.findViewById(R.id.tabsListContent), listHeight)
        setViewHeight(previews, listHeight)
        setViewHeight(icons, listHeight)
        setViewHeight(root.findViewById(R.id.emptyTabsMessage), listHeight)
        setViewHeight(panel, listHeight + footer)
    }

    private fun setViewHeight(view: View, height: Int) {
        if (view.layoutParams.height != height) {
            view.layoutParams = view.layoutParams.apply { this.height = height }
        }
    }

    private fun createAndSetupTabsTray(context: Context): TabsAdapter {
        val tabsTray = requireView().findViewById<RecyclerView>(R.id.tabsTray)
        val density = context.resources.displayMetrics.density
        val spanCount = ((requireView().width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels) / (158 * density).toInt()).coerceAtLeast(1)
        val layoutManager = GridLayoutManager(context, spanCount, RecyclerView.VERTICAL, false)
        val thumbnailLoader = ThumbnailLoader(context.components.core.thumbnailStorage)
        val trayStyling = TabsTrayStyling(itemBackgroundColor = Color.TRANSPARENT, itemTextColor = Color.WHITE)
        val viewHolderProvider: ViewHolderProvider = { viewGroup ->
            val view = LayoutInflater
                .from(context)
                .inflate(R.layout.browser_tabstray_item, viewGroup, false)

            DefaultTabViewHolder(view, thumbnailLoader)
        }
        val tabsAdapter = TabsAdapter(
            thumbnailLoader = thumbnailLoader,
            viewHolderProvider = viewHolderProvider,
            styling = trayStyling,
            delegate = object : TabsTray.Delegate {
                override fun onTabSelected(
                    tab: TabSessionState,
                    source: String?,
                ) {
                    requireComponents.useCases.tabsUseCases.selectTab(tab.id)
                    closeTabsTray()
                }

                override fun onTabClosed(
                    tab: TabSessionState,
                    source: String?,
                ) {
                    requireComponents.useCases.tabsUseCases.removeTab(tab.id)
                }
            },
        )

        tabsTray.layoutManager = layoutManager
        tabsTray.adapter = tabsAdapter
        tabsAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                val root = view ?: return
                root.post {
                    if (isAdded) updateTabsTrayHeight(root, tabsAdapter)
                }
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onChanged()

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onChanged()

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onChanged()
        })

        TabsTouchHelper {
            requireComponents.useCases.tabsUseCases.removeTab(it.id)
        }.attachToRecyclerView(tabsTray)

        return tabsAdapter
    }

}
