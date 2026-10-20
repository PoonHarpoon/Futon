package io.github.landwarderer.futon.reader.ui.pager.doublepage

import android.graphics.PointF
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import io.github.landwarderer.futon.core.exceptions.resolve.ExceptionResolver
import io.github.landwarderer.futon.core.os.NetworkState
import io.github.landwarderer.futon.databinding.ItemPageBinding
import io.github.landwarderer.futon.reader.domain.PageLoader
import io.github.landwarderer.futon.reader.ui.config.ReaderSettings
import io.github.landwarderer.futon.reader.ui.pager.ReaderPage
import io.github.landwarderer.futon.reader.ui.pager.standard.PageHolder

class DoublePageHolder(
	owner: LifecycleOwner,
	binding: ItemPageBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : PageHolder(
	owner = owner,
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
) {

	private val isEven: Boolean
		get() = bindingAdapterPosition and 1 == 0

	init {
		binding.ssiv.panLimit = SubsamplingScaleImageView.PAN_LIMIT_INSIDE
	}

	private var isSpacer = false

	override fun onBind(data: ReaderPage) {
		super.onBind(data)
		isSpacer = false
		binding.ssiv.isVisible = true
		binding.textViewNumber.isVisible = settings.isPagesNumbersEnabled
		(binding.textViewNumber.layoutParams as FrameLayout.LayoutParams)
			.gravity = (if (isEven) Gravity.START else Gravity.END) or Gravity.BOTTOM
	}

	/**
	 * Display a blank page, used to pad chapters so that two chapters never share a spread
	 */
	fun bindSpacer() {
		isSpacer = true
		viewModel.onRecycle()
		binding.ssiv.recycle()
		binding.ssiv.isVisible = false
		binding.textViewNumber.isVisible = false
		bindingInfo.layoutProgress.isGone = true
		bindingInfo.layoutError.isVisible = false
		settings.applyBackground(itemView)
	}

	override fun onConfigChanged(settings: ReaderSettings) {
		super.onConfigChanged(settings)
		if (isSpacer) {
			binding.textViewNumber.isVisible = false
		}
	}

	override fun onReady() {
		with(binding.ssiv) {
			maxScale = 2f * maxOf(
				width / sWidth.toFloat(),
				height / sHeight.toFloat(),
			)
			binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
			minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
			setScaleAndCenter(
				minScale,
				PointF(if (isEven) 0f else sWidth.toFloat(), sHeight / 2f),
			)
		}
	}
}
