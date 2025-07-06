package com.msp1974.vacompanion

import android.content.Context
import android.webkit.JavascriptInterface

/** Instantiate the interface and set the context.  */
class WebAppInterface(private val mContext: Context) {

    @JavascriptInterface
    fun getViewAssistCAUUID(): String {
        return Global.config.uuid
    }
}
