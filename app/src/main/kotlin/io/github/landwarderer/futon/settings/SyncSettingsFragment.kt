package io.github.landwarderer.futon.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentResultListener
import androidx.preference.ListPreference
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.BasePreferenceFragment
import io.github.landwarderer.futon.core.util.ext.getQuantityStringSafe
import io.github.landwarderer.futon.sync.data.SyncSettings
import io.github.landwarderer.futon.sync.ui.SyncHostDialogFragment
import javax.inject.Inject

@AndroidEntryPoint
class SyncSettingsFragment : BasePreferenceFragment(R.string.sync_settings), FragmentResultListener {

	@Inject
	lateinit var syncSettings: SyncSettings

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

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			SyncSettings.KEY_SYNC_URL -> {
				SyncHostDialogFragment.show(childFragmentManager, null)
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	override fun onFragmentResult(requestKey: String, result: Bundle) {
		bindHostSummary()
	}

	private fun bindHostSummary() {
		val preference = findPreference<Preference>(SyncSettings.KEY_SYNC_URL) ?: return
		preference.summary = syncSettings.syncUrl
	}
}
