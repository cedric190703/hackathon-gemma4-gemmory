package com.gemmory.core.logging

import android.util.Log
import com.gemmory.BuildConfig

/**
 * Thin logging facade.
 *
 * Privacy rule enforced by convention and reviewed in [com.gemmory.privacy]:
 * prompt and response text is NEVER passed to these functions. Only sizes,
 * durations, states and error classes are logged.
 */
object AppLog {

    private const val TAG = "Gemmory"

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[$tag] $message")
    }

    fun i(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(TAG, "[$tag] $message", throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$tag] $message", throwable)
    }

    /** Logs performance information in debug builds only. */
    fun perf(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "[perf/$tag] $message")
    }
}
