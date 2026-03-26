package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import kotlin.jvm.JvmOverloads
import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.webkit.*
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
import androidx.webkit.WebViewFeature
import com.msp1974.vacompanion.jsinterface.ViewAssistCallback
import com.msp1974.vacompanion.jsinterface.WebAppInterface
import com.msp1974.vacompanion.jsinterface.WebViewJavascriptInterface
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CustomWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    lateinit private var customWebviewClient: CustomWebViewClient
    lateinit private var config: APPConfig


    private val log = Logger()
    private var requestDisallow = false
    private val androidInterface: Any = object : Any() {
        @JavascriptInterface
        fun requestScrollEvents() {
            requestDisallow = true
        }
    }

    fun initialise(config: APPConfig, customWebViewClient: CustomWebViewClient) {
        log.d("Initialising WebView")

        this.config = config
        this.customWebviewClient = customWebViewClient

        webViewClient = customWebViewClient
        setFocusable(true)
        setFocusableInTouchMode(true)

        setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            safeBrowsingEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT

            webChromeClient = CustomWebChromeClient(context)
        }

        // Add JS interfaces
        addJavascriptInterface(androidInterface, "Android")
        if (webViewClient::class == CustomWebViewClient::class) {
            val webViewClientA = webViewClient as CustomWebViewClient
            addJavascriptInterface(WebAppInterface(webViewClientA.config, ViewAssistEventHandler), "ViewAssistApp")
            addJavascriptInterface(WebViewJavascriptInterface(this, AuthUtils(config).externalAuthCallback), "externalApp")
        }

    }

    val ViewAssistEventHandler = object : ViewAssistCallback {
        override fun onEvent(event: String, data: String) {
            //if (event == "location-changed") {
            //    Handler(Looper.getMainLooper()).post({
            //        setPageLoadingState(PageLoadingStage.LOADED)
            //    })
            //}
            Timber.d("Event received: $event, $data")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (requestDisallow) {
            requestDisallowInterceptTouchEvent(true)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> requestDisallow = false
        }
        return super.onTouchEvent(event)
    }

    fun refreshDarkMode() {
        val nightModeFlag = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlag == Configuration.UI_MODE_NIGHT_YES) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    settings,
                    WebSettingsCompat.FORCE_DARK_ON
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(
                    settings,
                    DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                )
            }
        }
    }

    fun setZoomLevel(level: Int) {
        if (level == 0) {
            settings.useWideViewPort = true
        } else {
            settings.useWideViewPort = false
            setInitialScale(level)
        }

    }

    fun setPageLoadingState(stage: PageLoadingStage) {
        val w = webViewClient as CustomWebViewClient
        w.setPageLoadingState(stage)
    }

    companion object {
        fun getView(context: Context): CustomWebView {
            return try {
                CustomWebView(context)
            } catch (e: NotFoundException) {
                CustomWebView(context.applicationContext)
            }
        }
    }
}