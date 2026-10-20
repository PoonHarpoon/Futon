package io.github.landwarderer.futon.sync.data

import dagger.Reusable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import io.github.landwarderer.futon.core.exceptions.SyncApiException
import io.github.landwarderer.futon.core.network.BaseHttpClient
import io.github.landwarderer.futon.core.util.ext.toRequestBody
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.removeSurrounding
import javax.inject.Inject

@Reusable
class SyncAuthApi @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {

	suspend fun authenticate(syncURL: String, email: String, password: String): String {
		val body = JSONObject(
			mapOf("email" to email, "password" to password),
		).toRequestBody()
		val request = Request.Builder()
			.url("$syncURL/auth")
			.post(body)
			.build()
		val response = okHttpClient.newCall(request).await()
		if (response.isSuccessful) {
			return response.parseJson().getString("token")
		} else {
			val code = response.code
			val message = response.parseRaw().removeSurrounding('"')
			throw SyncApiException(message, code)
		}
	}

	/** Asks the server to email a password reset link. Succeeds whether or not the account exists. */
	suspend fun forgotPassword(syncURL: String, email: String) {
		val body = JSONObject(
			mapOf("email" to email),
		).toRequestBody()
		postNoContent("$syncURL/forgot-password", body)
	}

	suspend fun resetPassword(syncURL: String, resetToken: String, password: String) {
		val body = JSONObject(
			mapOf("reset_token" to resetToken, "password" to password),
		).toRequestBody()
		postNoContent("$syncURL/reset-password", body)
	}

	private suspend fun postNoContent(url: String, body: RequestBody) {
		val request = Request.Builder()
			.url(url)
			.post(body)
			.build()
		okHttpClient.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw response.toSyncApiException()
			}
		}
	}

	/** Unlike [authenticate], does not show an HTML error page or an empty body to the user verbatim */
	private fun Response.toSyncApiException(): SyncApiException {
		val message = runCatching {
			parseRaw().removeSurrounding('"').trim()
		}.getOrNull()
		return if (message.isNullOrEmpty() || message.startsWith('<') || message.length > MAX_ERROR_LENGTH) {
			SyncApiException("HTTP $code", code)
		} else {
			SyncApiException(message, code)
		}
	}

	private companion object {

		const val MAX_ERROR_LENGTH = 200
	}
}
