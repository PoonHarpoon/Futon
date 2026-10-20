package io.github.landwarderer.futon.details.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.alternatives.ui.MangaUnavailableDialog
import io.github.landwarderer.futon.core.exceptions.UnsupportedSourceException
import io.github.landwarderer.futon.core.exceptions.resolve.ErrorObserver
import io.github.landwarderer.futon.core.exceptions.resolve.ExceptionResolver
import io.github.landwarderer.futon.core.model.isLocal
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.util.ext.getDisplayMessage
import io.github.landwarderer.futon.core.util.ext.isContentNotFound
import io.github.landwarderer.futon.core.util.ext.isNetworkError
import io.github.landwarderer.futon.core.util.ext.isSerializable
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga

class DetailsErrorObserver(
	override val activity: DetailsActivity,
	private val viewModel: DetailsViewModel,
	resolver: ExceptionResolver?,
) : ErrorObserver(
	activity.viewBinding.scrollView, null, resolver,
	{ isResolved ->
		if (isResolved) {
			viewModel.reload()
		}
	},
) {

	private var unavailableDialog: MangaUnavailableDialog? = null

	init {
		// The prompt is an activity-hosted dialog, so it has to go down with the activity.
		activity.lifecycle.addObserver(
			object : DefaultLifecycleObserver {
				override fun onDestroy(owner: LifecycleOwner) {
					unavailableDialog?.dismiss()
					unavailableDialog = null
				}
			},
		)
	}

	override suspend fun emit(value: Throwable) {
		// A title that is gone from its source is a dead end, so ask up front whether
		// to go looking for it on another source.
		val missingManga = viewModel.getMangaOrNull()?.takeIf { !it.isLocal && value.isContentNotFound() }
		if (missingManga != null && showUnavailableDialog(missingManga)) {
			return
		}
		val snackbar = Snackbar.make(host, value.getDisplayMessage(host.context.resources), Snackbar.LENGTH_SHORT)
		snackbar.setAnchorView(activity.viewBinding.containerBottomSheet)
		if (value is NotFoundException || value is UnsupportedSourceException) {
			snackbar.duration = Snackbar.LENGTH_INDEFINITE
		}
		when {
			canResolve(value) -> {
				snackbar.setAction(ExceptionResolver.getResolveStringId(value)) {
					resolve(value)
				}
			}

			value is ParseException -> {
				val router = router()
				if (router != null && value.isSerializable()) {
					snackbar.setAction(R.string.details) {
						router.showErrorDialog(value)
					}
				}
			}

			value.isNetworkError() -> {
				snackbar.setAction(R.string.try_again) {
					viewModel.reload()
				}
			}
		}
		snackbar.show()
	}

	/**
	 * @return `true` if the prompt is on screen and the caller should not show anything else.
	 * A dialog that is already up is left alone: reloads re-emit the same error.
	 */
	private fun showUnavailableDialog(manga: Manga): Boolean {
		if (unavailableDialog?.isShowing == true) {
			return true
		}
		if (activity.isDestroyed || activity.isFinishing) {
			return false
		}
		val router = activity.router
		unavailableDialog = MangaUnavailableDialog.Builder(activity, manga)
			.setOnAlternativesClickListener { router.openAlternatives(manga) }
			.create()
			.also { it.show() }
		return true
	}
}
