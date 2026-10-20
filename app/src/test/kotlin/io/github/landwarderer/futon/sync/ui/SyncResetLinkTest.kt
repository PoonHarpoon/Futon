package io.github.landwarderer.futon.sync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncResetLinkTest {

	@Test
	fun validLink() {
		val result = SyncResetLink.parse(" https://sync.futonapp.pages.dev/ ", " abc ")
		result as SyncResetLink.Result.Valid
		assertEquals("https://sync.futonapp.pages.dev", result.link.syncUrl)
		assertEquals("abc", result.link.token)
	}

	@Test
	fun keepsPathDropsQuery() {
		val result = SyncResetLink.parse("https://example.com/sync/?x=1#f", "t")
		result as SyncResetLink.Result.Valid
		assertEquals("https://example.com/sync", result.link.syncUrl)
	}

	@Test
	fun missingParts() {
		assertEquals(SyncResetLink.Result.Missing, SyncResetLink.parse(null, "t"))
		assertEquals(SyncResetLink.Result.Missing, SyncResetLink.parse("https://example.com", " "))
		assertEquals(SyncResetLink.Result.Missing, SyncResetLink.parse("not a url", "t"))
		assertEquals(SyncResetLink.Result.Missing, SyncResetLink.parse("ftp://example.com", "t"))
	}

	@Test
	fun rejectsCleartext() {
		assertEquals(SyncResetLink.Result.Insecure, SyncResetLink.parse("http://example.com", "t"))
	}

	@Test
	fun rejectsUserInfo() {
		assertEquals(
			SyncResetLink.Result.Missing,
			SyncResetLink.parse("https://sync.futonapp.pages.dev@evil.example", "t"),
		)
	}

	@Test
	fun sameServer() {
		val link = (SyncResetLink.parse("https://sync.futonapp.pages.dev", "t") as SyncResetLink.Result.Valid).link
		assertTrue(link.isSameServer("https://sync.futonapp.pages.dev"))
		assertTrue(link.isSameServer("https://SYNC.futonapp.pages.dev/"))
		assertFalse(link.isSameServer("http://sync.futonapp.pages.dev"))
		assertFalse(link.isSameServer("https://sync.futonapp.pages.dev:8443"))
		assertFalse(link.isSameServer("https://sync.futonapp.pages.dev.evil.example"))
		assertFalse(link.isSameServer("garbage"))
	}
}
