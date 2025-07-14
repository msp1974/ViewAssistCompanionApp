package com.msp1974.vacompanion.utils

import android.util.Log

class Logger {
    companion object {
        const val TAG = "ViewAssistCA"
    }
    fun d(message: String) {
        Log.d(TAG, message)
    }
    fun e(message: String) {
        Log.e(TAG, message)
    }
    fun i(message: String) {
        Log.i(TAG, message)
    }
    fun w(message: String) {
        Log.w(TAG, message)
    }
}