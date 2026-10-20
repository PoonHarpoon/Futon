package io.github.landwarderer.futon.sync.ui

import android.net.Uri
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A `futon://reset-password?base_url=…&token=…` link, as sent in the password reset email.
 *
 * The link can come from anywhere, so [baseUrl] is untrusted: the new password is sent to it.
 */
class SyncResetLink private constructor(
	val baseUrl: HttpUrl,
	val token: String,
) {

	/** The url the API calls are made against, without a trailing slash, as the sync urls are stored */
	val syncUrl: String
		get() = baseUrl.toString().removeSuffix("/")

	/** Same scheme, host and port: the only parts that decide where the password goes */
	fun isSameServer(syncUrl: String): Boolean {
		val other = syncUrl.toHttpUrlOrNull() ?: return false
		return other.scheme == baseUrl.scheme && other.host == baseUrl.host && other.port == baseUrl.port
	}

	sealed interface Result {

		data class Valid(val link: SyncResetLink) : Result

		/** No token or base url, or not a reset link at all */
		data object Missing : Result

		/** Not https: the new password would be sent in clear text */
		data object Insecure : Result
	}

	companion object {

		const val HOST = "reset-password"
		private const val PARAM_BASE_URL = "base_url"
		private const val PARAM_TOKEN = "token"

		fun parse(uri: Uri?): Result {
			if (uri == null || uri.host != HOST) {
				return Result.Missing
			}
			return parse(
				baseUrl = uri.getQueryParameter(PARAM_BASE_URL),
				token = uri.getQueryParameter(PARAM_TOKEN),
			)
		}

		fun parse(baseUrl: String?, token: String?): Result {
			val tokenValue = token?.trim()
			val baseUrlValue = baseUrl?.trim()
			if (tokenValue.isNullOrEmpty() || baseUrlValue.isNullOrEmpty()) {
				return Result.Missing
			}
			val url = baseUrlValue.toHttpUrlOrNull() ?: return Result.Missing
			if (!url.isHttps) {
				return Result.Insecure
			}
			if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
				// user info can be used to disguise the actual host
				return Result.Missing
			}
			val normalized = url.newBuilder()
				.query(null)
				.fragment(null)
				.build()
			return Result.Valid(SyncResetLink(normalized, tokenValue))
		}
	}
}
