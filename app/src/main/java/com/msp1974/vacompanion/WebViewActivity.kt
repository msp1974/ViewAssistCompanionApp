package com.msp1974.vacompanion

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.jsinterface.ExternalAuthCallback
import com.msp1974.vacompanion.jsinterface.WebAppInterface
import com.msp1974.vacompanion.jsinterface.WebViewJavascriptInterface
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.InterfaceConfigChangeListener
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils


class WebViewActivity : AppCompatActivity(), SwipeRefreshLayout.OnRefreshListener {
    private var log = Logger()
    private lateinit var config: APPConfig
    private lateinit var screen: ScreenUtils

    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        log.d("Starting WebViewActivity")
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()

        screen = ScreenUtils(this)
        config = APPConfig.getInstance(this)

        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.haWebview)
        swipeRefreshLayout = findViewById(R.id.swiperefresh)

        // Initial states
        setDarkMode(config.darkMode)
        swipeRefreshLayout?.setEnabled(config.swipeRefresh)
        screen.setDeviceBrightnessMode(config.screenAutoBrightness)
        if (!config.screenAutoBrightness) {
            setScreenBrightness(config.screenBrightness)
        }
        setScreenAlwaysOn(config.screenAlwaysOn)

        // Config change listeners to react to config changes sent by HA
        config.addChangeListener("screenBrightness", object : InterfaceConfigChangeListener {
            override fun onConfigChange(property: String) {
                log.i("Screen brightness changed to ${config.screenBrightness}")
                runOnUiThread {
                    setScreenBrightness(config.screenBrightness)
                }
            }
        })
        config.addChangeListener("swipeRefresh", object : InterfaceConfigChangeListener {
            override fun onConfigChange(property: String) {
                log.i("Swipe refresh changed to ${config.swipeRefresh}")
                runOnUiThread {
                    swipeRefreshLayout?.setEnabled(config.swipeRefresh)
                }
            }
        })
        config.addChangeListener("screenAutoBrightness", object : InterfaceConfigChangeListener {
            override fun onConfigChange(property: String) {
                log.i("Screen Auto Brightness changed to ${config.screenAutoBrightness}")
                runOnUiThread {
                    setScreenAutoBrightness(config.screenAutoBrightness)
                }
            }
        })
        config.addChangeListener("screenAlwaysOn", object : InterfaceConfigChangeListener {
            override fun onConfigChange(property: String) {
                log.i("Screen Always On changed to ${config.screenAlwaysOn}")
                runOnUiThread {
                    setScreenAlwaysOn(config.screenAlwaysOn)
                }
            }
        })
        config.addChangeListener("darkMode", object : InterfaceConfigChangeListener {
            override fun onConfigChange(property: String) {
                log.i("Dark mode changed to ${config.darkMode}")
                runOnUiThread {
                    setDarkMode(config.darkMode)
                }
            }
        })

        // Initiate broadcast receiver for action broadcasts
        var satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log.i("Handling broadcast in webview activity.  Event is ${intent.action}")
                if (intent.action == BroadcastSender.SATELLITE_STOPPED) {
                    runOnUiThread {
                        LocalBroadcastManager.getInstance(context).unregisterReceiver(this)
                        webView!!.removeAllViews()
                        webView!!.destroy()
                    }
                    finish()
                }
                if (intent.action == BroadcastSender.TOAST_MESSAGE) {
                    runOnUiThread {
                        val toast = Toast.makeText(context, intent.getStringExtra("extra"), Toast.LENGTH_SHORT)
                        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0);
                        toast.show()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BroadcastSender.SATELLITE_STOPPED)
            addAction(BroadcastSender.TOAST_MESSAGE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(satelliteBroadcastReceiver, filter)

        // Setup WebView and load html page
        swipeRefreshLayout?.setOnRefreshListener(this);

    }

    override fun onStart() {
        super.onStart()
        initialiseWebView(webView)
        loadInitURL()
    }

    fun getHAUrl(): String {
        if (config.homeAssistantURL == "") {
            return "http://${config.homeAssistantConnectedIP}:${config.homeAssistantHTTPPort}"
        }
        return config.homeAssistantURL
    }

    fun loadInitURL() {
        //If we have auth token, load the home assistant URL
        //If not, do auth
        if (config.accessToken != "") {
            // We have valid token , load url
            log.d("Have auth token, logging in...")
            webView!!.loadUrl(AuthUtils.getURL(getHAUrl()))
        } else {
            // We need to ask for login
            log.d("No auth token stored. Requesting login")
            webView!!.loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun initialiseWebView(view: WebView?) {
        if (view != null) {
            // Add javascript interface for view assist
            view.addJavascriptInterface(WebAppInterface(applicationContext), "ViewAssistApp")
            // Add JS interface for HA external auth support
            view.addJavascriptInterface(
                WebViewJavascriptInterface(externalAuthCallback),
                "externalApp"
            )

            view.setWebViewClient(object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    val swipe = view.parent as SwipeRefreshLayout
                    swipe.isRefreshing = true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val swipe = view.parent as SwipeRefreshLayout
                    swipe.isRefreshing = false
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    log.d("Webview render process gone: $detail")
                    if (webView!! == view) {
                        val container = swipeRefreshLayout?.parent as ViewGroup
                        val params = container.layoutParams
                        container.removeView(swipeRefreshLayout)
                        webView = null
                        swipeRefreshLayout = null
                        view.destroy()

                        val v = layoutInflater.inflate(R.layout.activity_webview, container, false)
                        webView = findViewById(R.id.haWebview)
                        swipeRefreshLayout = findViewById(R.id.swiperefresh)
                        initialiseWebView(webView)
                        setContentView(v, params)
                        webView?.loadUrl(AuthUtils.getURL(getHAUrl()))
                    }

                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    // If the url is our client id then capture the auth code and get an access token
                    if (url.contains(AuthUtils.CLIENT_URL)) {
                        val authCode = AuthUtils.getReturnAuthCode(url)
                        if (authCode != "") {
                            // Get access token using auth token
                            val auth = AuthUtils.authoriseWithAuthCode(getHAUrl(), authCode)
                            if (auth.accessToken == "") {
                                // Not authorised.  Send back to login screen
                                view.loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
                            } else {
                                // Authorised. Load HA default dashboard
                                config.accessToken = auth.accessToken
                                config.refreshToken = auth.refreshToken
                                config.tokenExpiry = auth.expires
                                view.loadUrl(AuthUtils.getURL(getHAUrl()))
                            }
                        }
                    }
                    return true
                }
            })

            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
            }
            view.removeAllViews()

        }

    }

    // Add external auth callback for HA authentication
    val externalAuthCallback = object : ExternalAuthCallback {
        override fun onRequestExternalAuth() {
            log.d("External auth callback in progress...")
            if (System.currentTimeMillis() > config.tokenExpiry && config.refreshToken != "") {
                // Need to get new access token as it has expired
                val success: Boolean = reAuthWithRefreshToken()
                if (success) {
                    log.d("Authorising with token")
                    callAuthJS()
                } else {
                    log.d("Failed to refresh auth token.  Proceeding to login screen")
                    runOnUiThread {
                        webView!!.loadUrl(AuthUtils.getAuthUrl(getHAUrl()))
                    }
                }
            } else {
                log.d("Auth token is still valid - authorising")
                callAuthJS()
            }
        }

        private fun callAuthJS() {
            runOnUiThread {
                webView!!.evaluateJavascript(
                    "window.externalAuthSetToken(true, {\n" +
                        "\"access_token\": \"${config.accessToken}\",\n" +
                        "\"expires_in\": 1800\n" +
                        "});",
                    null
                )
            }
        }

        private fun reAuthWithRefreshToken(): Boolean {
            log.i("Auth token has expired.  Requesting new token using refresh token")
            val auth = AuthUtils.refreshAccessToken(
                getHAUrl(),
                config.refreshToken
            )
            if (auth.accessToken != "" && auth.expires > System.currentTimeMillis()) {
                log.d("Received new auth token")
                config.accessToken = auth.accessToken
                config.tokenExpiry = auth.expires
                return true
            } else {
                return false
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignore back button
        if (false) {
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val visibility: Int
        if (hasFocus) {
            // Set current activity
            config.currentActivity = "WebViewActivity"

            ScreenUtils(this).hideStatusAndActionBars()
            actionBar?.hide()
        }
    }

    override fun onRefresh() {
        log.d("Reloading WebView URL")
        webView!!.removeAllViews()
        loadInitURL()
    }

    override fun onResume() {
        super.onResume()
        // Keep screen on
        setScreenAlwaysOn(config.screenAlwaysOn)
    }

    override fun onDestroy() {
        screen.setDeviceBrightnessMode(true)
        super.onDestroy()
    }

    fun setScreenBrightness(brightness: Float) {
        try {
            if (screen.canWriteScreenSetting()) {
                screen.setScreenBrightness((brightness * 255).toInt())
            } else {
                val layout: WindowManager.LayoutParams? = this.window?.attributes
                layout?.screenBrightness = brightness
                this.window?.attributes = layout
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setScreenAutoBrightness(state: Boolean) {
        if (!state) {
            screen.setDeviceBrightnessMode(false)
            setScreenBrightness(config.screenBrightness)
        } else {
            screen.setDeviceBrightnessMode(true)
        }
    }

    fun setScreenAlwaysOn(state: Boolean) {
        // wake lock
        if (state) {
            screen.wakeScreen()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.decorView.keepScreenOn = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.keepScreenOn = false
        }
    }

    fun setDarkMode(state: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView!!.settings, if (state) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }
    }
}