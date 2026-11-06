package io.github.landwarderer.futon.core.debuglog

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import io.github.landwarderer.futon.core.db.MangaDatabase
import io.github.landwarderer.futon.core.model.MangaSource
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.BaseViewModel
import io.github.landwarderer.futon.tracker.data.TrackEntity
import io.github.landwarderer.futon.tracker.work.TrackWorker
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class DebugLogViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
	private val db: MangaDatabase,
	private val workManager: WorkManager,
	private val trackScheduler: TrackWorker.Scheduler,
) : BaseViewModel() {

	val query = MutableStateFlow("")

	@OptIn(FlowPreview::class)
	val entries = combine(DebugLog.version.sample(SAMPLE_MS), query) { _, q ->
		val all = DebugLog.snapshot()
		if (q.isBlank()) all else all.filter { it.format().contains(q, ignoreCase = true) }
	}.flowOn(Dispatchers.Default)
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, DebugLog.snapshot())

	fun checkNow() {
		DebugLog.log(TAG, "Button: check for new chapters now")
		trackScheduler.startNow()
	}

	fun recheckBlocked() = launchJob(Dispatchers.IO) {
		val sources = db.getTracksDao().observeAll().first()
			.filter { it.track.lastResult == TrackEntity.RESULT_CAPTCHA }
			.map { it.manga.source }
			.distinct()
		DebugLog.log(TAG, "Button: re-check captcha-blocked tracks, sources=$sources")
		sources.forEach { trackScheduler.recheckSource(MangaSource(it)) }
	}

	fun reschedule() = launchJob(Dispatchers.IO) {
		DebugLog.log(TAG, "Button: re-run scheduler")
		trackScheduler.schedule()
		dumpWork()
	}

	fun cancelChecks() = launchJob(Dispatchers.IO) {
		DebugLog.log(TAG, "Button: cancel all running and queued checks")
		WORK_TAGS.forEach { workManager.cancelAllWorkByTag(it) }
		dumpWork()
	}

	fun dumpWork() = launchJob(Dispatchers.IO) {
		val infos = workManager.getWorkInfosFlow(WorkQuery.fromTags(WORK_TAGS)).first()
		DebugLog.log(TAG, "WorkManager: ${infos.size} tracker work items")
		infos.sortedBy { it.state.ordinal }.forEach { DebugLog.log(TAG, "  ${it.describe()}") }
	}

	fun dumpSettings() = launchJob(Dispatchers.IO) {
		val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
		DebugLog.log(
			TAG,
			"Settings: trackerEnabled=${settings.isTrackerEnabled} frequency=${settings.trackerFrequencyFactor} " +
				"wifiOnly=${settings.isTrackerWifiOnly} captchaRecheck=${settings.isTrackerCaptchaRecheckEnabled} " +
				"captchaAutoSolve=${settings.isCaptchaAutoSolveEnabled} unstuckDone=${settings.isTrackerUnstuckMigrationDone} " +
				"progressRefreshDone=${settings.isTrackerProgressRefreshDone} " +
				"notifications=${NotificationManagerCompat.from(context).areNotificationsEnabled()} " +
				"ignoresBatteryOptimizations=${power.isIgnoringBatteryOptimizations(context.packageName)} " +
				"sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}",
		)
	}

	fun dumpTracks() = launchJob(Dispatchers.IO) {
		val tracks = db.getTracksDao().observeAll().first()
		val now = System.currentTimeMillis()
		DebugLog.log(
			TAG,
			"Tracks: ${tracks.size} total, ${tracks.count { it.track.newChapters > 0 }} with new chapters " +
				"(${tracks.sumOf { it.track.newChapters }} chapters), never checked=${tracks.count { it.track.lastCheckTime == 0L }}, " +
				"by last result: " + tracks.groupingBy { resultName(it.track.lastResult) }.eachCount(),
		)
		tracks.sortedByDescending { it.track.lastCheckTime }.forEach { t ->
			val ago = if (t.track.lastCheckTime == 0L) "never" else "${(now - t.track.lastCheckTime) / 60_000}m ago"
			DebugLog.log(
				TAG,
				"  '${t.manga.title}' [${t.manga.source}] new=${t.track.newChapters} checked=$ago " +
					"result=${resultName(t.track.lastResult)} lastChapterId=${t.track.lastChapterId} " +
					"lastChapterDate=${Instant.ofEpochMilli(t.track.lastChapterDate)}" +
					(t.track.lastError?.let { " error=${it.take(120)}" } ?: ""),
			)
		}
	}

	fun mark(note: String) {
		DebugLog.log("Mark", "========== ${note.ifBlank { "mark" }} ==========")
	}

	fun clear() = DebugLog.clear()

	/**
	 * The full log with every manga title known to the database replaced by a stable token (`title#1`), and URLs
	 * cut down to their host, since their path usually carries the title. With [hideSources], source names and
	 * hosts are replaced as well (`source#1`, `<url>`).
	 */
	suspend fun exportSanitized(hideSources: Boolean): String = withContext(Dispatchers.IO) {
		val titles = LinkedHashSet<String>()
		val sources = LinkedHashSet<String>()
		db.openHelper.readableDatabase.query("SELECT title, source FROM manga").use { cursor ->
			while (cursor.moveToNext()) {
				cursor.getString(0)?.takeIf { it.length >= MIN_TITLE_LENGTH }?.let(titles::add)
				cursor.getString(1)?.let(sources::add)
			}
		}
		var text = DebugLog.exportText()
		text = URL_REGEX.replace(text) { match ->
			if (hideSources) "<url>" else "${match.groupValues[1]}/<redacted>"
		}
		text = text.replaceAllWhole(titles, "title")
		if (hideSources) {
			text = text.replaceAllWhole(sources, "source")
		}
		"# Sanitized Futon debug log: titles hidden" + (if (hideSources) ", sources hidden" else "") + "\n" + text
	}

	/**
	 * Replaces every one of [values] in a single pass with `prefix#N`, numbered by first appearance, so the same
	 * value always gets the same token. A value is only matched where it isn't part of a longer word, so a short
	 * title can't match inside other text; longer values are tried first, so one containing another wins.
	 */
	private fun String.replaceAllWhole(values: Collection<String>, prefix: String): String {
		if (values.isEmpty()) return this
		val alternation = values.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
		val regex = Regex("(?<![\\p{L}\\p{N}])(?:$alternation)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
		val tokens = HashMap<String, String>()
		return regex.replace(this) { match ->
			tokens.getOrPut(match.value.lowercase()) { "$prefix#${tokens.size + 1}" }
		}
	}

	private fun WorkInfo.describe(): String = buildString {
		append(state)
		append(" id=").append(id)
		append(" attempt=").append(runAttemptCount)
		append(" tags=").append(tags.filterNot { it.contains('.') })
		if (state == WorkInfo.State.ENQUEUED && nextScheduleTimeMillis != Long.MAX_VALUE) {
			append(" nextRunIn=").append((nextScheduleTimeMillis - System.currentTimeMillis()) / 60_000).append("m")
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason != WorkInfo.STOP_REASON_NOT_STOPPED) {
			append(" stopReason=").append(stopReason)
		}
		periodicityInfo?.let { append(" every=").append(it.repeatIntervalMillis / 60_000).append("m") }
	}

	private fun resultName(result: Int) = when (result) {
		TrackEntity.RESULT_NONE -> "none"
		TrackEntity.RESULT_HAS_UPDATE -> "update"
		TrackEntity.RESULT_NO_UPDATE -> "no_update"
		TrackEntity.RESULT_FAILED -> "failed"
		TrackEntity.RESULT_EXTERNAL_MODIFICATION -> "external"
		TrackEntity.RESULT_CAPTCHA -> "captcha"
		else -> "?$result"
	}

	private companion object {

		const val TAG = "Debug"
		const val SAMPLE_MS = 250L
		val WORK_TAGS = listOf("tracking", "tracking_oneshot", "tracking_recheck")
		const val MIN_TITLE_LENGTH = 2
		val URL_REGEX = Regex("(https?://[^/\\s]+)[^\\s,)]*")
	}
}
