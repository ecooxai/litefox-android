/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.Preference.OnPreferenceClickListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceCategory
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import mozilla.components.service.fxa.manager.SCOPE_PROFILE
import mozilla.components.service.fxa.manager.SCOPE_SYNC
import mozilla.components.support.ktx.android.view.showKeyboard
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.R.string.pref_key_about_page
import org.mozilla.reference.browser.R.string.pref_key_firefox_account
import org.mozilla.reference.browser.R.string.pref_key_make_default_browser
import org.mozilla.reference.browser.R.string.pref_key_override_amo_collection
import org.mozilla.reference.browser.R.string.pref_key_pair_sign_in
import org.mozilla.reference.browser.R.string.pref_key_privacy
import org.mozilla.reference.browser.R.string.pref_key_remote_debugging
import org.mozilla.reference.browser.R.string.pref_key_sign_in
import org.mozilla.reference.browser.autofill.AutofillPreference
import org.mozilla.reference.browser.apps.SavedApp
import org.mozilla.reference.browser.apps.SavedAppsRepository
import org.mozilla.reference.browser.ext.getPreferenceKey
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.sync.BrowserFxAEntryPoint
import kotlin.system.exitProcess

private typealias RBSettings = org.mozilla.reference.browser.settings.Settings

class SettingsFragment : PreferenceFragmentCompat() {
    interface ActionBarUpdater {
        fun updateTitle(titleResId: Int)
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()

        setupPreferences()
        getActionBarUpdater().apply {
            updateTitle(R.string.settings)
        }
    }

    private fun setupPreferences() {
        val signInKey = requireContext().getPreferenceKey(pref_key_sign_in)
        val signInPairKey = requireContext().getPreferenceKey(pref_key_pair_sign_in)
        val firefoxAccountKey = requireContext().getPreferenceKey(pref_key_firefox_account)
        val makeDefaultBrowserKey = requireContext().getPreferenceKey(pref_key_make_default_browser)
        val remoteDebuggingKey = requireContext().getPreferenceKey(pref_key_remote_debugging)
        val aboutPageKey = requireContext().getPreferenceKey(pref_key_about_page)
        val privacyKey = requireContext().getPreferenceKey(pref_key_privacy)
        val customAddonsKey = requireContext().getPreferenceKey(pref_key_override_amo_collection)
        val autofillPreferenceKey = requireContext().getPreferenceKey(R.string.pref_key_autofill)
        val dnsPreferenceKey = requireContext().getPreferenceKey(R.string.pref_key_dns_mode)
        val previewSearchEnginesKey = requireContext().getPreferenceKey(R.string.pref_key_preview_search_engines)
        val appsKey = requireContext().getPreferenceKey(R.string.pref_key_apps)
        val appsItemsKey = requireContext().getPreferenceKey(R.string.pref_key_apps_items)

        val preferenceSignIn = findPreference<Preference>(signInKey)
        val preferencePairSignIn = findPreference<Preference>(signInPairKey)
        val preferenceFirefoxAccount = findPreference<Preference>(firefoxAccountKey)
        val preferenceMakeDefaultBrowser = findPreference<Preference>(makeDefaultBrowserKey)
        val preferenceRemoteDebugging = findPreference<SwitchPreferenceCompat>(remoteDebuggingKey)
        val preferenceAboutPage = findPreference<Preference>(aboutPageKey)
        val preferencePrivacy = findPreference<Preference>(privacyKey)
        val preferenceCustomAddons = findPreference<Preference>(customAddonsKey)
        val preferenceAutofill = findPreference<AutofillPreference>(autofillPreferenceKey)
        val preferenceDns = findPreference<ListPreference>(dnsPreferenceKey)
        val preferencePreviewSearchEngines = findPreference<Preference>(previewSearchEnginesKey)
        val preferenceApps = findPreference<Preference>(appsKey)
        val preferenceAppsItems = findPreference<PreferenceCategory>(appsItemsKey)

        val accountManager = requireComponents.backgroundServices.accountManager
        if (accountManager.authenticatedAccount() != null) {
            preferenceSignIn?.isVisible = false
            preferencePairSignIn?.isVisible = false
            preferenceFirefoxAccount?.summary = accountManager.accountProfile()?.email.orEmpty()
            preferenceFirefoxAccount?.onPreferenceClickListener = getClickListenerForFirefoxAccount()
        } else {
            preferenceSignIn?.isVisible = true
            preferenceFirefoxAccount?.isVisible = false
            preferenceFirefoxAccount?.onPreferenceClickListener = null
            preferenceSignIn?.onPreferenceClickListener = getClickListenerForSignIn()
            preferencePairSignIn?.isVisible = true
            preferencePairSignIn?.onPreferenceClickListener = getClickListenerForPairingSignIn()
        }

        if (!AutofillPreference.isSupported(requireContext())) {
            preferenceAutofill?.isVisible = false
        } else {
            (preferenceAutofill as AutofillPreference).updateSwitch()
        }

        preferenceMakeDefaultBrowser?.onPreferenceClickListener = getClickListenerForMakeDefaultBrowser()
        preferenceRemoteDebugging?.onPreferenceChangeListener = getChangeListenerForRemoteDebugging()
        preferenceAboutPage?.onPreferenceClickListener = getAboutPageListener()
        preferencePrivacy?.onPreferenceClickListener = getClickListenerForPrivacy()
        preferenceCustomAddons?.onPreferenceClickListener = getClickListenerForCustomAddons()
        preferenceDns?.onPreferenceChangeListener = OnPreferenceChangeListener { preference, newValue ->
            val mode = newValue as? String ?: RBSettings.DNS_MODE_CLOUDFLARE
            RBSettings.applyDnsMode(requireComponents.core.engine.settings, mode)
            preference.summary = (preference as? ListPreference)?.entry
            true
        }
        preferencePreviewSearchEngines?.summary = previewSearchEnginesSummary()
        preferencePreviewSearchEngines?.onPreferenceClickListener =
            getClickListenerForPreviewSearchEngines(preferencePreviewSearchEngines)
        setupSavedAppsPreferences(preferenceApps, preferenceAppsItems)
    }

    private fun setupSavedAppsPreferences(
        header: Preference?,
        category: PreferenceCategory?,
        initiallyExpanded: Boolean = false,
    ) {
        if (header == null || category == null) return

        fun refresh(expanded: Boolean) {
            val apps = SavedAppsRepository(requireContext()).getAll()
            category.removeAll()
            if (apps.isEmpty()) {
                category.addPreference(
                    Preference(requireContext()).apply {
                        title = getString(R.string.preferences_apps_empty)
                        isSelectable = false
                    },
                )
            } else {
                apps.forEach { app ->
                    category.addPreference(
                        Preference(requireContext()).apply {
                            key = "saved-app-${SavedAppsRepository.normalize(app.url).hashCode()}"
                            title = app.title
                            summary = app.url
                            onPreferenceClickListener = OnPreferenceClickListener {
                                showEditSavedAppDialog(app, category.isVisible)
                                true
                            }
                        },
                    )
                }
            }
            category.isVisible = expanded && apps.isNotEmpty()
            header.summary = getString(R.string.preferences_apps_summary)
        }

        refresh(initiallyExpanded)
        header.onPreferenceClickListener = OnPreferenceClickListener {
            refresh(!category.isVisible)
            true
        }
    }

    private fun showEditSavedAppDialog(
        app: SavedApp,
        keepExpanded: Boolean,
    ) {
        val context = requireContext()
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val nameView = EditText(context).apply {
            hint = getString(R.string.preferences_app_name_hint)
            setSingleLine(true)
            setText(app.title)
        }
        val urlView = EditText(context).apply {
            hint = getString(R.string.preferences_app_url_hint)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(app.url)
        }
        fields.addView(nameView)
        fields.addView(urlView)

        AlertDialog.Builder(context)
            .setTitle(R.string.preferences_app_edit)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = SavedAppsRepository(context).update(
                    original = app,
                    title = nameView.text.toString(),
                    url = urlView.text.toString(),
                )
                if (updated) {
                    val appsKey = context.getPreferenceKey(R.string.pref_key_apps)
                    val appsItemsKey = context.getPreferenceKey(R.string.pref_key_apps_items)
                    setupSavedAppsPreferences(
                        findPreference(appsKey),
                        findPreference(appsItemsKey),
                        initiallyExpanded = keepExpanded,
                    )
                    Toast.makeText(context, R.string.preferences_app_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.preferences_app_invalid, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun previewSearchEnginesSummary(): String {
        val engines = RBSettings.getPreviewSearchEngines(requireContext())
        val defaultName = RBSettings.getDefaultPreviewSearchEngine(requireContext()).name
        val customNames = engines.drop(4).joinToString { it.name }
        return if (customNames.isBlank()) {
            "Default: $defaultName"
        } else {
            "Default: $defaultName · Custom: $customNames"
        }
    }

    private fun getClickListenerForPreviewSearchEngines(
        preference: Preference,
    ): OnPreferenceClickListener =
        OnPreferenceClickListener {
            val context = requireContext()
            val fields = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, 0, padding, 0)
            }
            val nameView = EditText(context).apply {
                hint = getString(R.string.preferences_preview_search_engine_name_hint)
                setSingleLine(true)
            }
            val urlView = EditText(context).apply {
                hint = getString(R.string.preferences_preview_search_engine_url_hint)
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            fields.addView(
                nameView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            fields.addView(
                urlView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            AlertDialog.Builder(context)
                .setTitle(R.string.preferences_preview_search_engine_add)
                .setMessage(getString(R.string.preferences_preview_search_engine_url_hint))
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val added = RBSettings.addPreviewSearchEngine(
                        context,
                        nameView.text.toString(),
                        urlView.text.toString(),
                    )
                    if (added) {
                        preference.summary = previewSearchEnginesSummary()
                        Toast.makeText(
                            context,
                            R.string.preferences_preview_search_engine_added,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            R.string.preferences_preview_search_engine_invalid,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .show()
            true
        }

    private fun getClickListenerForMakeDefaultBrowser(): OnPreferenceClickListener =
        OnPreferenceClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            )
            startActivity(intent)
            true
        }

    private fun getClickListenerForSignIn(): OnPreferenceClickListener =
        OnPreferenceClickListener {
            requireComponents.services.accountsAuthFeature.beginAuthentication(
                requireContext(),
                BrowserFxAEntryPoint.HomeMenu,
                setOf(SCOPE_PROFILE, SCOPE_SYNC),
            )
            activity?.finish()
            true
        }

    private fun getClickListenerForPairingSignIn(): OnPreferenceClickListener =
        OnPreferenceClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.container, PairSettingsFragment())
                .addToBackStack(null)
                .commit()
            getActionBarUpdater().apply {
                updateTitle(R.string.pair_preferences)
            }
            true
        }

    private fun getClickListenerForFirefoxAccount(): OnPreferenceClickListener =
        OnPreferenceClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.container, AccountSettingsFragment())
                .addToBackStack(null)
                .commit()
            getActionBarUpdater().apply {
                updateTitle(R.string.account_settings)
            }
            true
        }

    private fun getClickListenerForPrivacy(): OnPreferenceClickListener =
        OnPreferenceClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.container, PrivacySettingsFragment())
                .addToBackStack(null)
                .commit()
            getActionBarUpdater().apply {
                updateTitle(R.string.privacy_settings)
            }
            true
        }

    private fun getChangeListenerForRemoteDebugging(): OnPreferenceChangeListener =
        OnPreferenceChangeListener { _, newValue ->
            requireComponents.core.engine.settings.remoteDebuggingEnabled = newValue as Boolean
            true
        }

    private fun getAboutPageListener(): OnPreferenceClickListener =
        OnPreferenceClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.container, AboutFragment())
                .addToBackStack(null)
                .commit()
            true
        }

    private fun getActionBarUpdater() = activity as ActionBarUpdater

    private fun getClickListenerForCustomAddons(): OnPreferenceClickListener =
        OnPreferenceClickListener {
            val context = requireContext()
            val dialogView = View.inflate(context, R.layout.amo_collection_override_dialog, null)
            val userView = dialogView.findViewById<EditText>(R.id.custom_amo_user)
            val collectionView = dialogView.findViewById<EditText>(R.id.custom_amo_collection)

            AlertDialog
                .Builder(context)
                .apply {
                setTitle(context.getString(R.string.preferences_customize_amo_collection))
                setView(dialogView)
                setNegativeButton(R.string.customize_addon_collection_cancel) { dialog: DialogInterface, _ ->
                    dialog.cancel()
                }

                setPositiveButton(R.string.customize_addon_collection_ok) { _, _ ->
                    RBSettings.setOverrideAmoUser(context, userView.text.toString())
                    RBSettings.setOverrideAmoCollection(context, collectionView.text.toString())

                    Toast
                        .makeText(
                        context,
                        getString(R.string.toast_customize_addon_collection_done),
                        Toast.LENGTH_LONG,
                    ).show()

                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            exitProcess(0)
                        },
                        AMO_COLLECTION_OVERRIDE_EXIT_DELAY,
                    )
                }

                collectionView.setText(RBSettings.getOverrideAmoCollection(context))
                userView.setText(RBSettings.getOverrideAmoUser(context))
                userView.requestFocus()
                userView.showKeyboard()
                create()
            }.show()
            true
        }

    companion object {
        private const val AMO_COLLECTION_OVERRIDE_EXIT_DELAY = 3000L
    }
}
