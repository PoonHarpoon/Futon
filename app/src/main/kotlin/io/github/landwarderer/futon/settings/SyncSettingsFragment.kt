package io.github.landwarderer.futon.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentResultListener
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.BasePreferenceFragment
import io.github.landwarderer.futon.core.util.ext.getQuantityStringSafe
import io.github.landwarderer.futon.core.util.ext.viewLifecycleScope
import io.github.landwarderer.futon.sync.data.SyncSettings
import io.github.landwarderer.futon.sync.domain.SyncController
import io.github.landwarderer.futon.sync.ui.SyncHostDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class SyncSettingsFragment : BasePreferenceFragment(R.string.sync_settings), FragmentResultListener {

	@Inject
	lateinit var syncSettings: SyncSettings

	@Inject
	lateinit var syncController: SyncController

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_sync)
		bindHostSummary()
		bindSyncPeriodEntries()
	}

	private fun bindSyncPeriodEntries() {
		val preference = findPreference<ListPreference>(AppSettings.KEY_SYNC_PERIOD) ?: return
		val periods = AppSettings.SYNC_PERIODS
		preference.entryValues = Array(periods.size) { periods[it].toString() }
		preference.entries = Array(periods.size) { i ->
			val hours = periods[i]
			if (hours == 0) {
				getString(R.string.disabled)
			} else {
				resources.getQuantityStringSafe(R.plurals.hours, hours, hours)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		childFragmentManager.setFragmentResultListener(SyncHostDialogFragment.REQUEST_KEY, viewLifecycleOwner, this)
	}

	override fun onResume() {
		super.onResume()
		bindSummaries()
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			SyncSettings.KEY_SYNC -> {
				val account = peekAccount()
				if (account == null) {
					syncController.addAccount(requireActivity()) {
						onAccountChanged()
					}
				} else {
					if (!router.openSystemSyncSettings(account)) {
						Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
					}
				}
				true
			}

			SyncSettings.KEY_SYNC_URL -> {
				SyncHostDialogFragment.show(childFragmentManager, null)
				true
			}

			SyncSettings.KEY_LOGOUT -> {
				peekAccount()?.let { confirmLogout(it) }
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	override fun onFragmentResult(requestKey: String, result: Bundle) {
		bindHostSummary()
	}

	private fun confirmLogout(account: Account) {
		MaterialAlertDialogBuilder(context ?: return)
			.setTitle(R.string.logout)
			.setMessage(getString(R.string.logged_in_as, account.name))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.logout) { _, _ ->
				syncController.removeAccount(requireActivity(), account) {
					onAccountChanged()
				}
			}.show()
	}

	/** AccountManager callbacks may arrive after the view is gone */
	private fun onAccountChanged() {
		if (view != null) {
			bindSummaries()
		}
	}

	private fun peekAccount(): Account? {
		val accountType = getString(R.string.account_type_sync)
		return AccountManager.get(requireContext()).getAccountsByType(accountType).firstOrNull()
	}

	private fun bindSummaries() {
		bindHostSummary()
		bindSyncSummary()
	}

	private fun bindHostSummary() {
		val preference = findPreference<Preference>(SyncSettings.KEY_SYNC_URL) ?: return
		preference.summary = syncSettings.syncUrl
	}

	private fun bindSyncSummary() {
		viewLifecycleScope.launch {
			val account = withContext(Dispatchers.Default) {
				peekAccount()
			}
			findPreference<Preference>(SyncSettings.KEY_SYNC)?.run {
				summary = when {
					account == null -> getString(R.string.sync_login)
					syncController.isEnabled(account) -> {
						val enabledSync = ArrayList<String>(2)
						if (syncController.isFavouritesEnabled(account)) enabledSync.add(getString(R.string.favourites))
						if (syncController.isHistoryEnabled(account)) enabledSync.add(getString(R.string.history))
						account.name + enabledSync.joinToString(", ", " (", ")")
					}

					else -> getString(R.string.disabled)
				}
			}
			findPreference<Preference>(SyncSettings.KEY_SYNC_URL)?.isEnabled = account != null
			findPreference<Preference>(SyncSettings.KEY_LOGOUT)?.isEnabled = account != null
			findPreference<Preference>(AppSettings.KEY_SYNC_PERIOD)?.isEnabled = account != null
		}
	}
}
