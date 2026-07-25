package com.gemmory.privacy

import com.gemmory.BuildConfig
import com.gemmory.core.logging.AppLog
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Debug-only visibility into outbound traffic.
 *
 * The app is supposed to make exactly one kind of request: fetching the model
 * file. Every other host is logged loudly during development so an accidental
 * analytics or inference dependency cannot slip in unnoticed.
 *
 * This is an auditing aid, not a security control: it only covers OkHttp.
 */
object NetworkAccessAuditor {

    private const val TAG = "NetworkAudit"

    /** Hosts the app is allowed to contact, and only for model downloads. */
    private val expectedHostSuffixes = listOf("huggingface.co", "hf.co", "cdn-lfs.huggingface.co")

    fun wrap(client: OkHttpClient): OkHttpClient {
        if (!BuildConfig.DEBUG) return client
        return client.newBuilder().addInterceptor(AuditInterceptor()).build()
    }

    private class AuditInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val host = request.url.host
            val expected = expectedHostSuffixes.any { host == it || host.endsWith(".$it") }
            if (expected) {
                AppLog.d(TAG, "model download request to $host")
            } else {
                AppLog.w(
                    TAG,
                    "UNEXPECTED outbound request to $host — inference must never touch the network",
                )
            }
            return chain.proceed(request)
        }
    }
}
