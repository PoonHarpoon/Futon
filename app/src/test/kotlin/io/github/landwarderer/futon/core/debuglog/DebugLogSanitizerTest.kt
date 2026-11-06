package io.github.landwarderer.futon.core.debuglog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogSanitizerTest {

	private fun sanitize(
		log: String,
		titles: List<String> = emptyList(),
		sources: List<String> = emptyList(),
		hideSources: Boolean = false,
	) = DebugLogSanitizer(titles, sources, hideSources).sanitize(log).substringAfter('\n')

	@Test
	fun `marked title, branch and chapter names are replaced`() {
		val line = "OK ${DebugLog.title("Frieren")} [MANGADEX] new=2 valid=true branch=${DebugLog.branch("Harmless Monsters")}" +
			" -> ${DebugLog.chapter(12f, "Frieren meets Himmel")}, ${DebugLog.chapter(13f, null)}"
		val out = sanitize(line)
		assertEquals("OK 'title#1' [MANGADEX] new=2 valid=true branch=branch#1 -> #12, #13", out)
	}

	@Test
	fun `the same title gets the same token, whatever the pass that finds it`() {
		val log = "Compare ${DebugLog.title("Smiley")}: 76 chapters\nCompare 'Smiley': 76 chapters\nerror in smiley"
		val out = sanitize(log, titles = listOf("Smiley"))
		assertEquals("Compare 'title#1': 76 chapters\nCompare 'title#1': 76 chapters\nerror in title#1", out)
	}

	@Test
	fun `legacy quoted titles are replaced even when unknown or short`() {
		val log = "OK 'N' [MANGADEX] new=0\nCompare 'Monmusugo!: Honnyaku Skill': 42 chapters\n'Oz' failed"
		val out = sanitize(log)
		assertFalse(out, out.contains("Monmusugo") || out.contains("'N'") || out.contains("Oz"))
	}

	@Test
	fun `apostrophes in error text are not taken for titles`() {
		val out = sanitize("FAIL 'x' [S] ParseException: Can't parse 'chapter'")
		assertTrue(out, out.endsWith("Can't parse 'chapter'"))
	}

	@Test
	fun `legacy branches and chapter lists are redacted`() {
		val log = "OK 'x' [S] new=1 valid=true branch=Harmless Monsters -> Ch. 12: Frieren meets Himmel\n" +
			"Compare 'y': 92 chapters, branch=English (1), track(new=1)\n" +
			"OK 'y' [S] new=2 valid=true branch=null -> # 216 Himmel's return, # 217\n" +
			"Filter 'x' dropped 1 of 1 new chapters (readChapters=1, own history=1, similar titles=0): Chapter 2 (stale)"
		val out = sanitize(log)
		assertFalse(out, out.contains("Harmless") || out.contains("Himmel") || out.contains("Chapter 2") || out.contains("English"))
		assertTrue(out, out.contains("branch=branch#1"))
	}

	@Test
	fun `every url shape keeps at most its site`() {
		val log = listOf(
			"at https://site.com?search=one+piece",
			"at https://site.com/manga/one-piece,vol-2/ch-1",
			"at https://site.com/Berserk_(manga)_list",
			"at https://user:pw@site.com/x",
			"URL=[https://www.mangatown.com/manga/one_piece/]",
			"ParseException: Cannot find x at /manga/one-piece/",
			"src //cdn.site.com/one-piece/1.jpg",
		).joinToString("\n")
		val out = sanitize(log)
		listOf("one", "piece", "Berserk", "manga/", "pw", "user").forEach { word ->
			assertFalse("$word in $out", out.contains(word, ignoreCase = true))
		}
		assertTrue(out, out.contains("https://site.com/<redacted>"))
	}

	@Test
	fun `hidden sources hide sites everywhere, bare hosts included`() {
		val log = "FAIL [MANGADEX] UnknownHostException: Unable to resolve host \"mangadex.org\"\nat https://mangadex.org/title/x"
		val out = sanitize(log, sources = listOf("MANGADEX"), hideSources = true)
		assertFalse(out, out.contains("mangadex", ignoreCase = true))
	}

	@Test
	fun `response bodies, ids and the device are dropped`() {
		val log = "SerializationException: JSON input: <html><title>One Piece - Read</title>\n" +
			"track(new=0 lastId=-1881086594089425747 at index 1) history(chapterId=42 at index 1)\n" +
			"Settings: sdk=36 device=samsung SM-S936"
		val out = sanitize(log)
		listOf("One Piece", "1881086594089425747", "chapterId=42", "SM-S936").forEach { assertFalse(out, out.contains(it)) }
	}

	@Test
	fun `titles that are words of the log do not rewrite it`() {
		val out = sanitize("[Tracker] Worker START kind=manual", titles = listOf("Tracker", "Worker"))
		assertEquals("[Tracker] Worker START kind=manual", out)
	}

	@Test
	fun `one-word titles only match with their own capitalization`() {
		val out = sanitize("Recheck deferred: another check is running\nerror loading Another", titles = listOf("Another"))
		assertEquals("Recheck deferred: another check is running\nerror loading title#1", out)
	}

	@Test
	fun `titles are matched across unicode forms but not inside longer words`() {
		val out = sanitize("ＯＮＥ ＰＩＥＣＥ and Pokémon and नमस्ते", titles = listOf("One Piece", "Pokémon", "नमस"))
		assertEquals("title#1 and title#2 and नमस्ते", out)
	}
}
