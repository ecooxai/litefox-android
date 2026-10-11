/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import mozilla.components.concept.toolbar.Toolbar;
import mozilla.components.feature.toolbar.WebExtensionToolbarAction;
import mozilla.components.feature.toolbar.WebExtensionToolbarFeature;

/** Safe Java bridge for the generated accessors exposed by WebExtensionToolbarFeature. */
public final class ExtensionToolbarCompat {
    private static final Map<Toolbar, ArrayList<ManagedAction>> MANAGED_ACTIONS = new WeakHashMap<>();

    private ExtensionToolbarCompat() {}

    public static void sync(
            WebExtensionToolbarFeature feature,
            Toolbar toolbar,
            Set<String> pinnedExtensionIds) {
        Map<String, ? extends Toolbar.Action> browserActions =
                feature.getWebExtensionBrowserActions$feature_toolbar_release();
        Map<String, ? extends Toolbar.Action> pageActions =
                feature.getWebExtensionPageActions$feature_toolbar_release();

        synchronized (MANAGED_ACTIONS) {
            ArrayList<ManagedAction> previous = MANAGED_ACTIONS.remove(toolbar);
            if (previous != null) {
                for (ManagedAction managed : previous) {
                    managed.removeFrom(toolbar);
                }
            }

            // WebExtensionToolbarFeature may have added its own copy. Remove every
            // current copy from each container before installing our exact set.
            for (Toolbar.Action action : browserActions.values()) {
                removeAllBrowserActions(toolbar, action);
            }
            for (Toolbar.Action action : pageActions.values()) {
                removeAllPageActions(toolbar, action);
            }

            ArrayList<ManagedAction> current = new ArrayList<>();
            Set<String> addedExtensionIds = new HashSet<>();
            for (String extensionId : pinnedExtensionIds) {
                if (!addedExtensionIds.add(extensionId)) {
                    continue;
                }

                Toolbar.Action browserAction = browserActions.get(extensionId);
                if (browserAction != null) {
                    toolbar.addBrowserAction(browserAction);
                    current.add(new ManagedAction(browserAction, false));
                    continue;
                }

                Toolbar.Action pageAction = pageActions.get(extensionId);
                if (pageAction != null) {
                    toolbar.addPageAction(pageAction);
                    current.add(new ManagedAction(pageAction, true));
                }
            }
            MANAGED_ACTIONS.put(toolbar, current);
        }
        toolbar.invalidateActions();
    }

    /** Refreshes icon state without rebuilding the toolbar action list. */
    public static void refresh(Toolbar toolbar) {
        toolbar.invalidateActions();
    }

    private static void removeAllBrowserActions(Toolbar toolbar, Toolbar.Action action) {
        // Toolbar implementations do not expose their action count. A bounded
        // loop removes stale duplicates while keeping this safe if a provider
        // reports an action that was not currently attached.
        for (int index = 0; index < 32; index++) {
            toolbar.removeBrowserAction(action);
        }
    }

    private static void removeAllPageActions(Toolbar toolbar, Toolbar.Action action) {
        for (int index = 0; index < 32; index++) {
            toolbar.removePageAction(action);
        }
    }

    /** Invokes the same popup/action listener used by a toolbar extension icon. */
    public static void open(WebExtensionToolbarFeature feature, String extensionId) {
        WebExtensionToolbarAction action =
                feature.getWebExtensionBrowserActions$feature_toolbar_release().get(extensionId);
        if (action == null) {
            action = feature.getWebExtensionPageActions$feature_toolbar_release().get(extensionId);
        }
        if (action != null) {
            action.getListener$feature_toolbar_release().invoke();
        }
    }

    private static final class ManagedAction {
        private final Toolbar.Action action;
        private final boolean pageAction;

        private ManagedAction(Toolbar.Action action, boolean pageAction) {
            this.action = action;
            this.pageAction = pageAction;
        }

        private void removeFrom(Toolbar toolbar) {
            if (pageAction) {
                removeAllPageActions(toolbar, action);
            } else {
                removeAllBrowserActions(toolbar, action);
            }
        }
    }
}
