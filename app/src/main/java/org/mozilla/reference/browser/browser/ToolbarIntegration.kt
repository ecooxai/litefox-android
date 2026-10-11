/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.icons.IconRequest
import mozilla.components.browser.domains.autocomplete.ShippedDomainsProvider
import mozilla.components.browser.menu2.BrowserMenuController
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.menu.MenuController
import mozilla.components.concept.menu.candidate.CompoundMenuCandidate
import mozilla.components.concept.menu.candidate.ContainerStyle
import mozilla.components.concept.menu.candidate.DrawableMenuIcon
import mozilla.components.concept.menu.candidate.MenuCandidate
import mozilla.components.concept.menu.candidate.RowMenuCandidate
import mozilla.components.concept.menu.candidate.SmallMenuCandidate
import mozilla.components.concept.menu.candidate.TextMenuCandidate
import mozilla.components.feature.pwa.WebAppUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.feature.toolbar.ToolbarAutocompleteFeature
import mozilla.components.feature.toolbar.ToolbarFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.addons.AddonsActivity
import org.mozilla.reference.browser.apps.ExtensionPinsRepository
import org.mozilla.reference.browser.apps.SavedAppsRepository
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.share
import org.mozilla.reference.browser.settings.SettingsActivity
import org.mozilla.reference.browser.tabs.synced.SyncedTabsActivity

@Suppress("LongParameterList")
class ToolbarIntegration(
    private val context: Context,
    private val toolbar: BrowserToolbar,
    historyStorage: PlacesHistoryStorage,
    private val store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val tabsUseCases: TabsUseCases,
    private val webAppUseCases: WebAppUseCases,
    private val showApps: (() -> Unit)? = null,
    private val showDownloads: (() -> Unit)? = null,
    private val onPinnedExtensionsChanged: () -> Unit = {},
    private val onExtensionClicked: (String) -> Unit = {},
    private val onFullscreenClicked: () -> Unit = {},
    sessionId: String? = null,
) : LifecycleAwareFeature,
    UserInteractionHandler {
    private val shippedDomainsProvider = ShippedDomainsProvider().also {
        it.initialize(context)
    }

    private val scope = MainScope()
    private val savedAppsRepository = SavedAppsRepository(context)
    private val extensionPinsRepository = ExtensionPinsRepository(context)

    private fun leaveUrlEditing() {
        toolbar.clearFocus()
    }

    private fun loadSavedAppIcon(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        scope.launch {
            val icon = runCatching {
                context.components.core.icons.loadIcon(
                    IconRequest(
                        url = url,
                        size = IconRequest.Size.DEFAULT,
                        resources = emptyList(),
                        color = null,
                        isPrivate = false,
                        waitOnNetworkLoad = true,
                    ),
                ).await()
            }.getOrNull() ?: return@launch
            val iconData = withContext(Dispatchers.Default) {
                SavedAppsRepository.encodeIcon(icon.bitmap)
            } ?: return@launch
            savedAppsRepository.updateIcon(url, iconData)
        }
    }

    private fun menuToolbar(session: SessionState?): RowMenuCandidate {
        val tint = ContextCompat.getColor(context, R.color.icons)

        val back = SmallMenuCandidate(
            contentDescription = "Back",
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_back_24,
                tint = tint,
            ),
            containerStyle = ContainerStyle(
                isEnabled = session?.content?.canGoBack == true,
            ),
        ) {
            leaveUrlEditing()
            sessionUseCases.goBack.invoke()
        }

        val forward = SmallMenuCandidate(
            contentDescription = "Forward",
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_forward_24,
                tint = tint,
            ),
            containerStyle = ContainerStyle(
                isEnabled = session?.content?.canGoForward == true,
            ),
        ) {
            leaveUrlEditing()
            sessionUseCases.goForward.invoke()
        }

        val refresh = SmallMenuCandidate(
            contentDescription = "Refresh",
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_arrow_clockwise_24,
                tint = tint,
            ),
        ) {
            leaveUrlEditing()
            sessionUseCases.reload.invoke()
        }

        val stop = SmallMenuCandidate(
            contentDescription = "Stop",
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_cross_24,
                tint = tint,
            ),
        ) {
            leaveUrlEditing()
            sessionUseCases.stopLoading.invoke()
        }

        return RowMenuCandidate(listOf(back, forward, refresh, stop))
    }

    private fun sessionMenuItems(sessionState: SessionState): List<MenuCandidate> =
        listOfNotNull(
            menuToolbar(sessionState),
            TextMenuCandidate("Share") {
                leaveUrlEditing()
                val url = sessionState.content.url
                context.share(url)
            },
            TextMenuCandidate(context.getString(R.string.menu_action_add_to_apps)) {
                leaveUrlEditing()
                val url = sessionState.content.url
                savedAppsRepository.addOrUpdate(
                    url = url,
                    title = sessionState.content.title.ifBlank { sessionState.content.url },
                    tabId = sessionState.id,
                )
                loadSavedAppIcon(url)
            },
            showApps?.let {
                TextMenuCandidate(context.getString(R.string.menu_action_show_apps)) {
                    leaveUrlEditing()
                    it()
                }
            },
            showDownloads?.let {
                TextMenuCandidate("Downloads") {
                    leaveUrlEditing()
                    it()
                }
            },
            CompoundMenuCandidate(
                text = "Request desktop site",
                isChecked = sessionState.content.desktopMode,
                end = CompoundMenuCandidate.ButtonType.SWITCH,
            ) { checked ->
                leaveUrlEditing()
                sessionUseCases.requestDesktopSite.invoke(checked)
            },
            if (webAppUseCases.isPinningSupported()) {
                TextMenuCandidate(
                    text = "Add to homescreen",
                    containerStyle = ContainerStyle(
                        isVisible = webAppUseCases.isPinningSupported(),
                    ),
                ) {
                    leaveUrlEditing()
                    scope.launch { webAppUseCases.addToHomescreen() }
                }
            } else {
                null
            },
            TextMenuCandidate(
                text = "Find in Page",
            ) {
                leaveUrlEditing()
                FindInPageIntegration.launch?.invoke()
            },
        )

    private fun menuItems(sessionState: SessionState?): List<MenuCandidate> {
        val sessionMenuItems = if (sessionState != null) {
            sessionMenuItems(sessionState)
        } else {
            emptyList()
        }

        return extensionMenuItems() + sessionMenuItems + listOf(
            TextMenuCandidate(text = context.getString(R.string.menu_action_fullscreen)) {
                leaveUrlEditing()
                onFullscreenClicked()
            },
            TextMenuCandidate(text = "Add-ons") {
                leaveUrlEditing()
                val intent = Intent(context, AddonsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            },
            TextMenuCandidate(text = "Synced Tabs") {
                leaveUrlEditing()
                val intent = Intent(context, SyncedTabsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            },
            TextMenuCandidate(text = "Report issue") {
                leaveUrlEditing()
                tabsUseCases.addTab(
                    url = "https://github.com/mozilla-mobile/reference-browser/issues/new",
                )
            },
            TextMenuCandidate(text = "Settings") {
                leaveUrlEditing()
                val intent = Intent(context, SettingsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            },
        )
    }

    private fun extensionMenuItems(): List<MenuCandidate> =
        store.state.extensions.values
            .sortedBy { it.name.orEmpty().lowercase() }
            .map { extension ->
                val extensionName = extension.name.orEmpty().ifBlank { extension.id }
                TextMenuCandidate(text = extensionName) {
                    leaveUrlEditing()
                    extensionPinsRepository.touch(extension.id)
                    onPinnedExtensionsChanged()
                    onExtensionClicked(extension.id)
                    browserMenuController.submitList(menuItems(store.state.selectedTab))
                }
            }

    private val browserMenuController: MenuController = BrowserMenuController()

    init {
        toolbar.display.apply {
            // Keep the bottom bar focused on navigation and user-pinned actions.
            // The security/tracking shields duplicate information already shown
            // by the page and consume valuable space on small screens.
            indicators = emptyList()
            displayIndicatorSeparator = true
            menuController = browserMenuController
            hint = context.getString(R.string.toolbar_hint)

            setUrlBackground(
                ResourcesCompat.getDrawable(context.resources, R.drawable.url_background, context.theme),
            )
        }

        toolbar.edit.apply {
            hint = context.getString(R.string.toolbar_hint)
        }

        ToolbarAutocompleteFeature(toolbar).apply {
            updateAutocompleteProviders(
                listOf(historyStorage, shippedDomainsProvider),
            )
        }

        scope.launch {
            store
                .flow()
                .collect { state ->
                    browserMenuController.submitList(menuItems(state.selectedTab))
                }
        }
    }

    private val toolbarFeature: ToolbarFeature = ToolbarFeature(
        toolbar,
        context.components.core.store,
        context.components.useCases.sessionUseCases.loadUrl,
        { searchTerms ->
            context.components.useCases.searchUseCases.defaultSearch.invoke(
                searchTerms = searchTerms,
                searchEngine = null,
                parentSessionId = null,
            )
        },
        sessionId,
    )

    override fun start() {
        toolbarFeature.start()
    }

    override fun stop() {
        toolbarFeature.stop()
    }

    override fun onBackPressed(): Boolean = toolbarFeature.onBackPressed()
}
