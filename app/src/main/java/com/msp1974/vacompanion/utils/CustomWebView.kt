package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import kotlin.jvm.JvmOverloads
import android.content.Context
import android.view.MotionEvent
import android.content.res.Resources.NotFoundException
import android.util.AttributeSet
import android.webkit.*
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
import androidx.webkit.WebViewFeature
import com.msp1974.vacompanion.data.NetworkStatus
import com.msp1974.vacompanion.jsinterface.ViewAssistCallback
import com.msp1974.vacompanion.jsinterface.WebAppInterface
import com.msp1974.vacompanion.jsinterface.WebViewJavascriptInterface
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.authentication.AuthenticationException
import com.msp1974.vacompanion.jsinterface.ExternalAuthCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CustomWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private lateinit var customWebviewClient: CustomWebViewClient
    private lateinit var deviceManager: DeviceManager
    private val config get() = deviceManager.config
    private var reAuthRequired: Boolean = false
    private var revokeRequired: Boolean = false

    private val gestureDetector = WebViewGestureDetector()
    private val log = Logger()
    private var requestDisallow = false
    private val androidInterface: Any = object : Any() {
        @JavascriptInterface
        fun requestScrollEvents() {
            requestDisallow = true
        }
    }

    fun setOnGestureListener(listener: WebViewGestureDetector.OnGestureListener) {
        gestureDetector.setOnGestureListener(listener)
    }

    fun initialise(deviceManager: DeviceManager, customWebViewClient: CustomWebViewClient) {
        log.d("Initialising WebView")

        this.deviceManager = deviceManager
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
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT

            webChromeClient = CustomWebChromeClient(context)
        }

        refreshDarkMode(config.darkMode)

        // Add JS interfaces
        removeJavascriptInterface("Android")
        addJavascriptInterface(androidInterface, "Android")

        if (webViewClient::class == CustomWebViewClient::class) {
            val webViewClientA = webViewClient as CustomWebViewClient
            removeJavascriptInterface("ViewAssistApp")
            addJavascriptInterface(WebAppInterface(webViewClientA.config, ViewAssistEventHandler), "ViewAssistApp")

            removeJavascriptInterface("externalApp")
            addJavascriptInterface(WebViewJavascriptInterface(this, externalAuthCallback), "externalApp")
        }

        scope.launch {
            deviceManager.networkStatus.collect {
                if (it.status == NetworkStatus.Available) {
                    if (reAuthRequired) {
                        Timber.d("Requesting previously postponed re-authorisation")
                        reAuthRequired = false
                        requestAuthorisation()
                    }
                    if (revokeRequired) {
                        revokeRequired = false
                        deviceManager.authenticationManager.revokeSession()
                    }
                }
            }
        }
    }

    suspend fun requestAuthorisation(forceRefresh: Boolean = false) {
        try {
            if (config.refreshToken != "") {
                deviceManager.authenticationManager.ensureValidSession(forceRefresh)
                withContext(Dispatchers.Main) {
                    callAuthJS()
                }
            } else {
                withContext(Dispatchers.Main) {
                    loadUrl(deviceManager.authenticationManager.getExternalAuthUrl())
                }
            }
        } catch (ex: AuthenticationException) {
            Timber.e("Error authenticating with HA: ${ex.message}")
        }
    }


    val externalAuthCallback = object : ExternalAuthCallback {
        override fun onRequestExternalAuth(view: WebView, payload: String) {
            if (deviceManager.networkStatusManager.networkInfo.status == NetworkStatus.Available) {
                val json = Json { ignoreUnknownKeys = true }
                val payloadJson = json.parseToJsonElement(payload).jsonObject
                val forceRefresh = payloadJson["force"]?.jsonPrimitive?.boolean ?: false
                scope.launch {
                    requestAuthorisation(forceRefresh)
                }
            } else {
                Timber.w("Requested authentication with HA while network was unavailable")
                reAuthRequired = true
            }
        }
        override fun onRequestRevokeExternalAuth(view: WebView) {
            if (deviceManager.networkStatusManager.networkInfo.status == NetworkStatus.Available) {
                scope.launch {
                    deviceManager.authenticationManager.revokeSession()
                }
            } else {
                Timber.w("Requested authentication revoke when network unavailable")
            }
        }

    }

    private fun callAuthJS() {
        evaluateJavascript(
            "window.externalAuthSetToken(true, {\n" +
                    "\"access_token\": \"${config.accessToken}\",\n" +
                    "\"expires_in\": ${((config.tokenExpiry - System.currentTimeMillis())/1000).toInt()}\n" +
                    "});",
            null
        )
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
        val gestureHandled = gestureDetector.onTouchEvent(event, height)
        if (requestDisallow) {
            requestDisallowInterceptTouchEvent(true)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> requestDisallow = false
        }

        //Prevent scrolling if more than 1 finger is used
        if (event.pointerCount > 1 || gestureHandled) {
            return true
        }

        return super.onTouchEvent(event)
    }

    fun refreshDarkMode(isDark: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isDark)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                settings,
                if (isDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }

        if (isDark && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(
                settings,
                DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
            )
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

    fun setTextSize(level: Int) {
        if (level == 0) {
            settings.textZoom = 100
        } else {
            settings.textZoom = level
        }

    }

    fun setPageLoadingState(stage: PageLoadingStage) {
        val w = webViewClient as CustomWebViewClient
        w.setPageLoadingState(stage)
    }

    fun refresh() {
        val url = deviceManager.authenticationManager.getHAUrl()
        log.d("CustomWebView - refresh -> Loading URL: $url")
        loadUrl(url)
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
