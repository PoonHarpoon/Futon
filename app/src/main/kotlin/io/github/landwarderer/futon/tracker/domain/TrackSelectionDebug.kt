package io.github.landwarderer.futon.tracker.domain

import io.github.landwarderer.futon.tracker.domain.model.MangaTracking
import java.util.concurrent.TimeUnit

/*
 * Debug log helpers: why a track was picked for a check, mirroring the conditions of TracksDao.findAll with the
 * windows of GetTracksUseCase (keep them in sync). A track can match several conditions; the first one listed
 * here is reported. "reading/favourite" covers the history and favourites conditions, which need the database
 * and are only inferred: what is left once the other conditions don't apply.
 */

private val INACTIVE_MS = TimeUnit.DAYS.toMillis(90)
private val STALE_CHECK_MS = TimeUnit.DAYS.toMillis(7)

internal fun MangaTracking.selectionReason(now: Long = System.currentTimeMillis()): String {
	val lastCheckMs = lastCheck?.toEpochMilli() ?: 0L
	val lastChapterMs = lastChapterDate?.toEpochMilli() ?: 0L
	return when {
		lastCheckMs == 0L -> "never-checked"
		lastChapterMs == 0L -> "no-chapter-date"
		lastChapterMs >= now - INACTIVE_MS -> "active"
		lastCheckMs <= now - STALE_CHECK_MS -> "weekly-sweep"
		else -> "reading/favourite"
	}
}

/** How long ago the track was last checked, e.g. `3h`, `8d`, or `never`. */
internal fun MangaTracking.lastCheckAge(now: Long = System.currentTimeMillis()): String {
	val lastCheckMs = lastCheck?.toEpochMilli() ?: return "never"
	if (lastCheckMs == 0L) return "never"
	val minutes = (now - lastCheckMs) / 60_000
	return when {
		minutes < 60 -> "${minutes}m"
		minutes < 48 * 60 -> "${minutes / 60}h"
		else -> "${minutes / (24 * 60)}d"
	}
}
