package io.github.landwarderer.futon.core.debuglog

import java.text.Normalizer

/**
 * Makes an exported [DebugLog] safe to share: hides what the user reads.
 *
 * The values that identify it are marked where they are logged (see [DebugLog.title]), so they are replaced
 * reliably. Lines written before the markers existed, links, error texts and every title known to the database
 * are scrubbed on top, as a backstop:
 * - titles become `title#N` and branches `branch#N`, the same value always getting the same token;
 * - chapter names are dropped, chapter numbers kept;
 * - links keep only their site (or nothing, with [hideSources]), response bodies in errors are dropped;
 * - chapter ids become `id#N`, the device model is dropped;
 * - with [hideSources], source names and sites become `source#N` and `<host>`.
 */
internal class DebugLogSanitizer(
	knownTitles: Collection<String>,
	private val knownSources: Collection<String>,
	private val hideSources: Boolean,
) {

	private val knownTitles = knownTitles.mapNotNull { title ->
		normalize(title).trim().takeIf { it.length >= MIN_TITLE_LENGTH && it.lowercase() !in STOP_WORDS }
	}.distinct()

	private val titleTokens = HashMap<String, String>()
	private val branchTokens = HashMap<String, String>()
	private val idTokens = HashMap<String, String>()
	private val sourceTokens = HashMap<String, String>()

	fun sanitize(log: String): String {
		var text = normalize(log)
		val hosts = if (hideSources) collectHosts(text) else emptySet()
		text = ERROR_BODY_REGEX.replace(text) { "${it.groupValues[1]} <redacted>" }
		text = HTML_REGEX.replace(text, "<html redacted>")
		text = URL_REGEX.replace(text) { if (hideSources) "<url>" else "${it.groupValues[1]}${it.groupValues[2]}/<redacted>" }
		text = RELATIVE_URL_REGEX.replace(text) { if (hideSources) "<url>" else "//${it.groupValues[1]}/<redacted>" }
		text = PATH_REGEX.replace(text, "/<redacted>")
		// Marked values
		text = CHAPTER_MARK_REGEX.replace(text, "")
		text = DebugLog.MARK_REGEX.replace(text) { match ->
			val value = match.groupValues[2]
			when (match.groupValues[1]) {
				DebugLog.KIND_TITLE -> titleTokens.tokenFor(value, "title")
				DebugLog.KIND_BRANCH -> branchTokens.tokenFor(value, "branch")
				else -> ""
			}
		}
		// Lines logged before the markers existed
		text = QUOTED_TITLE_REGEX.replace(text) { match ->
			val value = match.groupValues[1]
			"'" + (if (TOKEN_REGEX.matches(value)) value else titleTokens.tokenFor(value, "title")) + "'"
		}
		text = LEGACY_BRANCH_REGEX.replace(text) { branchTokens.tokenFor(it.value, "branch") }
		text = LEGACY_CHAPTERS_REGEX.replace(text) { "${it.groupValues[1]} <chapter names redacted>" }
		// Every known title, wherever it appears
		// A one-word title is often an ordinary word too ("Another"): only match it with its own capitalization
		val (singleWord, multiWord) = knownTitles.partition { title -> title.none { it.isWhitespace() } }
		text = text.replaceWhole(multiWord, titleTokens, "title")
		text = text.replaceWhole(singleWord, titleTokens, "title", ignoreCase = false)
		text = ID_REGEX.replace(text) { idTokens.tokenFor(it.value, "id") }
		text = DEVICE_REGEX.replace(text, "device=<redacted>")
		if (hideSources) {
			text = text.replaceWhole(hosts, HashMap(), "<host>", numbered = false)
			text = text.replaceWhole(knownSources.map(::normalize), sourceTokens, "source")
		}
		return "# Sanitized Futon debug log: titles, branches, chapter names and links hidden" +
			(if (hideSources) ", sources and sites hidden" else "") +
			". Notes you added with Mark are kept as typed.\n" + text
	}

	private fun collectHosts(text: String): Set<String> = buildSet {
		URL_REGEX.findAll(text).mapTo(this) { it.groupValues[2].substringBefore(':') }
		RELATIVE_URL_REGEX.findAll(text).mapTo(this) { it.groupValues[1] }
		UNRESOLVED_HOST_REGEX.findAll(text).mapTo(this) { it.groupValues[1] }
	}.filterTo(HashSet()) { it.contains('.') }

	/**
	 * Replaces every one of [values] in a single pass, only where it isn't part of a longer word, longest first.
	 * Values that don't occur in the text are left out of the pattern, which keeps it small.
	 */
	private fun String.replaceWhole(
		values: Collection<String>,
		tokens: MutableMap<String, String>,
		prefix: String,
		numbered: Boolean = true,
		ignoreCase: Boolean = true,
	): String {
		val present = values.filter { it.isNotEmpty() && contains(it, ignoreCase) }
		if (present.isEmpty()) return this
		val alternation = present.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
		val pattern = "(?<![\\p{L}\\p{M}\\p{N}])(?:$alternation)(?![\\p{L}\\p{M}\\p{N}])"
		val regex = if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
		return regex.replace(this) { match ->
			if (numbered) tokens.tokenFor(match.value, prefix) else prefix
		}
	}

	private fun MutableMap<String, String>.tokenFor(value: String, prefix: String): String =
		getOrPut(value.trim().lowercase()) { "$prefix#${size + 1}" }

	private companion object {

		const val MIN_TITLE_LENGTH = 3

		/** Words of the log itself: a manga with one of these as its title must not rewrite the log. */
		val STOP_WORDS = setOf(
			"tracker", "worker", "compare", "filter", "captcha", "debug", "mark", "app", "start", "end", "skip",
			"fail", "failed", "cancelled", "manual", "periodic", "recheck", "source", "sources", "title", "branch",
			"chapter", "chapters", "url", "host", "redacted", "none", "null", "true", "false", "new", "valid",
			"success", "result", "picked", "tracks", "track", "solved", "wait", "error", "stale", "the", "and",
		)

		val TOKEN_REGEX = Regex("(?:title|branch|source|id)#\\d+")
		val ERROR_BODY_REGEX = Regex("(JSON input:|Unexpected JSON token at offset \\d+:)[^\\n]*")
		val HTML_REGEX = Regex("<(?:!doctype|html|head|title|body|div|script|meta)\\b[^\\n]*", RegexOption.IGNORE_CASE)
		val URL_REGEX = Regex("(https?://)(?:[^\\s/@\\]\"'<>]+@)?([A-Za-z0-9.-]+(?::\\d+)?)[^\\s\\]\"'<>]*")
		val RELATIVE_URL_REGEX = Regex("(?<![:\\w/])//([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)[^\\s\\]\"'<>]*")
		val PATH_REGEX = Regex("(?<=\\sat )/[^\\s\\]\"'<>]+")
		val UNRESOLVED_HOST_REGEX = Regex("resolve host \"([^\"]+)\"")
		val CHAPTER_MARK_REGEX = Regex(" ?\\u27E6c:.*?\\u27E7")
		val QUOTED_TITLE_REGEX = Regex("(?<=\\s)'(.+?)'(?=\\s\\[|:\\s|\\sdropped|\\sfailed)")
		val LEGACY_BRANCH_REGEX = Regex(
			"(?<=branch=)(?!null\\b|branch#)[^,\\n]+?(?=,\\s|\\s(?:new|valid)=|\\s->|$)",
			RegexOption.MULTILINE,
		)

		/** Chapter lists in lines logged before the markers: after " -> " or a Filter line's "): ". */
		val LEGACY_CHAPTERS_REGEX = Regex("( -> |similar titles=\\d+\\): )(?!#\\d|#\\?|new=)[^\\n]+")
		val ID_REGEX = Regex("(?<=(?:lastId|lastChapterId|chapterId)=)-?\\d+")
		val DEVICE_REGEX = Regex("device=[^\\n]*")

		fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
	}
}
