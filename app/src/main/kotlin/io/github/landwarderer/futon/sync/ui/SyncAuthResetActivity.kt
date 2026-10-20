package io.github.landwarderer.futon.sync.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.core.ui.util.DefaultTextWatcher
import io.github.landwarderer.futon.core.util.ext.consumeAllSystemBarsInsets
import io.github.landwarderer.futon.core.util.ext.getDisplayMessage
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.core.util.ext.observeEvent
import io.github.landwarderer.futon.core.util.ext.systemBarsInsets
import io.github.landwarderer.futon.databinding.ActivitySyncAuthResetBinding

private const val PASSWORD_MIN_LENGTH = 4

/**
 * Handles the `futon://reset-password` link from a password reset email.
 */
@AndroidEntryPoint
class SyncAuthResetActivity : BaseActivity<ActivitySyncAuthResetBinding>(), View.OnClickListener,
	DefaultTextWatcher {

	private val viewModel by viewModels<SyncAuthResetViewModel>()
	private var serverDialog: AlertDialog? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySyncAuthResetBinding.inflate(layoutInflater))

		val parsed = if (intent.action == Intent.ACTION_VIEW) {
			SyncResetLink.parse(intent.data)
		} else {
			SyncResetLink.Result.Missing
		}
		val link = when (parsed) {
			SyncResetLink.Result.Missing -> return abort(R.string.no_reset_token)
			SyncResetLink.Result.Insecure -> return abort(R.string.sync_reset_insecure_url)
			is SyncResetLink.Result.Valid -> parsed.link
		}
		viewModel.setLink(link)

		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.editPassword.addTextChangedListener(this)
		viewBinding.editPasswordConfirm.addTextChangedListener(this)
		viewBinding.textViewServer.text = getString(R.string.server_address) + ": " + link.baseUrl.host

		viewModel.onPasswordResetSucceeded.observeEvent(this) { onPasswordResetSucceeded() }
		viewModel.onError.observeEvent(this, ::onError)
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.isServerConfirmed.observe(this, ::onServerConfirmationChanged)
	}

	override fun onDestroy() {
		serverDialog?.dismiss()
		serverDialog = null
		super.onDestroy()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.updatePadding(top = barsInsets.top)
		viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left
			rightMargin = barsInsets.right
			bottomMargin = barsInsets.bottom
		}
		val basePadding = viewBinding.layoutContent.paddingBottom
		viewBinding.layoutContent.updatePadding(
			left = barsInsets.left + basePadding,
			right = barsInsets.right + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> {
				setResult(RESULT_CANCELED)
				finish()
			}

			R.id.button_done -> {
				viewModel.resetPassword(viewBinding.editPassword.text.toString())
			}
		}
	}

	override fun afterTextChanged(s: Editable?) {
		val isLoading = viewModel.isLoading.value
		val password = viewBinding.editPassword.text?.toString()
		val passwordConfirm = viewBinding.editPasswordConfirm.text?.toString()
		viewBinding.buttonDone.isEnabled = !isLoading &&
			viewModel.isServerConfirmed.value == true &&
			password != null &&
			password.length >= PASSWORD_MIN_LENGTH &&
			password == passwordConfirm
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		with(viewBinding) {
			progressBar.isInvisible = !isLoading
			editPassword.isEnabled = !isLoading
			editPasswordConfirm.isEnabled = !isLoading
		}
		afterTextChanged(null)
	}

	private fun onServerConfirmationChanged(isConfirmed: Boolean?) {
		afterTextChanged(null)
		if (isConfirmed != false || serverDialog != null) {
			return
		}
		val host = viewModel.link.value?.baseUrl?.host ?: return
		serverDialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.sync_auth_reset_title)
			.setMessage(getString(R.string.sync_reset_unknown_server, host))
			.setCancelable(false)
			.setNegativeButton(android.R.string.cancel) { _, _ ->
				setResult(RESULT_CANCELED)
				finish()
			}
			.setPositiveButton(R.string._continue) { _, _ ->
				viewModel.confirmServer()
			}
			.setOnDismissListener { serverDialog = null }
			.show()
	}

	private fun onError(error: Throwable) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.error)
			.setMessage(error.getDisplayMessage(resources))
			.setNegativeButton(R.string.close, null)
			.show()
	}

	private fun onPasswordResetSucceeded() {
		Toast.makeText(this, R.string.password_reset, Toast.LENGTH_SHORT).show()
		setResult(RESULT_OK)
		finishAfterTransition()
	}

	private fun abort(@StringRes message: Int) {
		Toast.makeText(this, message, Toast.LENGTH_LONG).show()
		finish()
	}
}
