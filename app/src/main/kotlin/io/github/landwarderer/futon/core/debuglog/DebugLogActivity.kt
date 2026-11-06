package io.github.landwarderer.futon.core.debuglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.core.util.ext.consumeAllSystemBarsInsets
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.core.util.ext.printStackTraceDebug
import io.github.landwarderer.futon.core.util.ext.systemBarsInsets
import io.github.landwarderer.futon.databinding.ActivityDebugLogBinding
import java.io.File

/**
 * Live view of [DebugLog]: filter it, tap an entry to read or copy it in full, long-press to copy one line,
 * export everything still on disk, and trigger the background work being debugged.
 */
@AndroidEntryPoint
class DebugLogActivity : BaseActivity<ActivityDebugLogBinding>() {

	private val viewModel by viewModels<DebugLogViewModel>()
	private val adapter = EntriesAdapter()
	private var isFollowing = true

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDebugLogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		viewBinding.recyclerView.adapter = adapter
		viewBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
				// Scrolling up to read stops following; reaching the bottom again resumes it
				if (newState == RecyclerView.SCROLL_STATE_IDLE) {
					isFollowing = !recyclerView.canScrollVertically(1)
					invalidateOptionsMenu()
				}
			}
		})
		viewBinding.editFilter.setText(viewModel.query.value)
		viewBinding.editFilter.doAfterTextChanged { viewModel.query.value = it?.toString().orEmpty() }
		with(viewBinding) {
			actionCheckNow.setOnClickListener { viewModel.checkNow() }
			actionRecheckBlocked.setOnClickListener { viewModel.recheckBlocked() }
			actionWork.setOnClickListener { viewModel.dumpWork() }
			actionTracks.setOnClickListener { viewModel.dumpTracks() }
			actionSettings.setOnClickListener { viewModel.dumpSettings() }
			actionReschedule.setOnClickListener { viewModel.reschedule() }
			actionCancel.setOnClickListener { viewModel.cancelChecks() }
			actionMark.setOnClickListener { showMarkDialog() }
		}
		viewModel.entries.observe(this) { list ->
			adapter.submitList(list) {
				if (isFollowing && list.isNotEmpty()) {
					viewBinding.recyclerView.scrollToPosition(list.lastIndex)
				}
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.opt_debug_log, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.findItem(R.id.action_follow)?.isChecked = isFollowing
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		R.id.action_follow -> {
			isFollowing = !isFollowing
			if (isFollowing && adapter.itemCount > 0) {
				viewBinding.recyclerView.scrollToPosition(adapter.itemCount - 1)
			}
			invalidateOptionsMenu()
			true
		}

		R.id.action_copy -> {
			copy(adapter.currentList.joinToString("\n") { it.readable() })
			true
		}

		R.id.action_export -> {
			export("futon-debug-log.txt") { DebugLog.exportText() }
			true
		}

		R.id.action_export_sanitized -> {
			showSanitizeDialog()
			true
		}

		R.id.action_clear -> {
			viewModel.clear()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.recyclerView.updatePadding(bottom = barsInsets.bottom)
		viewBinding.root.updatePadding(left = barsInsets.left, right = barsInsets.right)
		viewBinding.appbar.updatePadding(top = barsInsets.top)
		return insets.consumeAllSystemBarsInsets()
	}

	private fun showMarkDialog() {
		val input = EditText(this).apply {
			hint = getString(R.string.debug_log_mark_hint)
			inputType = InputType.TYPE_CLASS_TEXT
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.debug_log_mark)
			.setView(input)
			.setPositiveButton(android.R.string.ok) { _, _ -> viewModel.mark(input.text.toString()) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun showEntry(entry: DebugLog.Entry) {
		val text = entry.readable()
		val view = TextView(this).apply {
			setText(text)
			setTextIsSelectable(true)
			typeface = Typeface.MONOSPACE
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			val padding = resources.getDimensionPixelSize(R.dimen.list_spacing_normal) * 2
			setPadding(padding, padding, padding, 0)
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.debug_log_entry)
			.setView(view)
			.setPositiveButton(android.R.string.copy) { _, _ -> copy(text) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun copy(text: String) {
		getSystemService(ClipboardManager::class.java)
			.setPrimaryClip(ClipData.newPlainText(getString(R.string.debug_log), text))
		Toast.makeText(this, R.string.debug_log_copied, Toast.LENGTH_SHORT).show()
	}

	private fun showSanitizeDialog() {
		val hideSources = booleanArrayOf(false)
		MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.debug_log_export_sanitized))
			.setMultiChoiceItems(arrayOf(getString(R.string.debug_log_hide_sources)), hideSources) { _, _, checked ->
				hideSources[0] = checked
			}
			.setPositiveButton(R.string.debug_log_export) { _, _ ->
				export("futon-debug-log-sanitized.txt") { viewModel.exportSanitized(hideSources[0]) }
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
		Toast.makeText(this, R.string.debug_log_export_sanitized_summary, Toast.LENGTH_LONG).show()
	}

	private fun export(fileName: String, content: suspend () -> String) {
		lifecycleScope.launch {
			Toast.makeText(this@DebugLogActivity, R.string.debug_log_preparing, Toast.LENGTH_SHORT).show()
			// Nothing is written unless the content was produced in full: a failed sanitization must not
			// leave an unsanitized file to share
			val file = runCatchingCancellable {
				withContext(Dispatchers.IO) {
					val text = content()
					File(cacheDir, fileName).apply { writeText(text) }
				}
			}.getOrElse { e ->
				e.printStackTraceDebug("DebugLogActivity::export")
				Toast.makeText(this@DebugLogActivity, e.toString(), Toast.LENGTH_LONG).show()
				return@launch
			}
			val uri = FileProvider.getUriForFile(this@DebugLogActivity, "$packageName.files", file)
			val intent = Intent(Intent.ACTION_SEND)
				.setType("text/plain")
				.putExtra(Intent.EXTRA_STREAM, uri)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			startActivity(Intent.createChooser(intent, getString(R.string.debug_log_export)))
		}
	}

	private inner class EntriesAdapter : ListAdapter<DebugLog.Entry, EntryHolder>(EntryDiff) {

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
			val view = TextView(parent.context).apply {
				layoutParams = RecyclerView.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT,
				)
				typeface = Typeface.MONOSPACE
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
				setPadding(0, 2, 0, 2)
				maxLines = MAX_LINES_COLLAPSED
				val outValue = TypedValue()
				context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
				setBackgroundResource(outValue.resourceId)
			}
			return EntryHolder(view)
		}

		override fun onBindViewHolder(holder: EntryHolder, position: Int) {
			val entry = getItem(position)
			holder.textView.text = entry.readable()
			holder.textView.alpha = if (entry.tag == DebugLog.RESTORED_TAG) 0.6f else 1f
			holder.textView.setOnClickListener { showEntry(entry) }
			holder.textView.setOnLongClickListener {
				copy(entry.readable())
				true
			}
		}
	}

	private fun DebugLog.Entry.readable(): String =
		DebugLog.display(if (tag == DebugLog.RESTORED_TAG) message else format())

	private class EntryHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

	private object EntryDiff : DiffUtil.ItemCallback<DebugLog.Entry>() {

		override fun areItemsTheSame(oldItem: DebugLog.Entry, newItem: DebugLog.Entry) = oldItem.seq == newItem.seq

		override fun areContentsTheSame(oldItem: DebugLog.Entry, newItem: DebugLog.Entry) = oldItem == newItem
	}

	private companion object {

		const val MAX_LINES_COLLAPSED = 6
	}
}
