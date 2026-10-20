package io.github.landwarderer.futon.sync.ui

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseViewModel
import io.github.landwarderer.futon.core.util.ext.MutableEventFlow
import io.github.landwarderer.futon.core.util.ext.call
import io.github.landwarderer.futon.sync.data.SyncAuthApi
import io.github.landwarderer.futon.sync.data.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class SyncAuthResetViewModel @Inject constructor(
	@ApplicationContext context: Context,
	private val api: SyncAuthApi,
	private val syncSettingsProvider: Provider<SyncSettings>,
) : BaseViewModel() {

	private val knownSyncUrls = context.resources.getStringArray(R.array.sync_url_list)

	val onPasswordResetSucceeded = MutableEventFlow<Unit>()
	val link = MutableStateFlow<SyncResetLink?>(null)

	/**
	 * Whether the new password may be sent to the link's server: null while checking, false until the user
	 * confirms a server that is neither a built-in one nor the one they are logged in to.
	 */
	val isServerConfirmed = MutableStateFlow<Boolean?>(null)

	fun setLink(value: SyncResetLink) {
		if (link.value != null) {
			return // already set up, e.g. after a configuration change
		}
		link.value = value
		launchJob(Dispatchers.IO) {
			val currentSyncUrl = syncSettingsProvider.get().syncUrl
			isServerConfirmed.value = (knownSyncUrls + currentSyncUrl).any { value.isSameServer(it) }
		}
	}

	fun confirmServer() {
		isServerConfirmed.value = true
	}

	fun resetPassword(password: String) {
		val linkValue = link.value ?: return
		if (isServerConfirmed.value != true) {
			return
		}
		launchLoadingJob(Dispatchers.IO) {
			api.resetPassword(linkValue.syncUrl, linkValue.token, password)
			onPasswordResetSucceeded.call(Unit)
		}
	}
}
