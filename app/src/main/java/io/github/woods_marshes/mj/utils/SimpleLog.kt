package io.github.woods_marshes.mj.utils

import android.util.Log
import io.github.woods_marshes.mj.BuildConfig

object SimpleLog {
    fun d(tag: String?, content: String) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        Log.d(tag, content)
    }
}