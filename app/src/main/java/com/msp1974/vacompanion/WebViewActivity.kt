package com.msp1974.vacompanion

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.reflect.KProperty


public class WebViewActivity : AppCompatActivity(), SwipeRefreshLayout.OnRefreshListener {
    private var config: Config = Global.config
    private var status: Status = Global.status

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var webView: WebView

    fun setScreenBrightness(brightness: Float) {
        val layout: WindowManager.LayoutParams? = this.window?.attributes
        layout?.screenBrightness = brightness
        this.window?.attributes = layout
    }

    fun pressPowerButton() {
        Runtime.getRuntime().exec("input keyevent KEYCODE_POWER")
    }

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()

        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.haWebview)
        webView.addJavascriptInterface(WebAppInterface(this), "ViewAssistApp")
        swipeRefreshLayout = findViewById(R.id.swiperefresh)

        // Initial states
        swipeRefreshLayout.setEnabled(config.swipeRefresh)
        setScreenBrightness(config.screenBrightness)

        config.configChangeListeners.add(object : InterfaceConfigChangeListener {
            @SuppressLint("SetJavaScriptEnabled")
            override fun onConfigChange(property: KProperty<*>, oldValue: Any, newValue: Any) {
                // Screen on/off
                if (property.name == "screenState") {
                    pressPowerButton()
                }

                if (property.name == "screenBrightness") {
                    // Set screen brightness
                    runOnUiThread {
                        setScreenBrightness(newValue as Float)
                    }
                }
                // enable/disable pull down screen refresh
                if (property.name == "swipeRefresh") {
                    runOnUiThread {
                        swipeRefreshLayout.setEnabled(newValue as Boolean)
                    }
                }
            }
        })

        status.connectionStateChangeListeners.add(object : InterfaceStatusChangeListener {
            override fun onStatusChange(property: KProperty<*>, oldValue: Any, newValue: Any) {
                if (property.name == "connectionState" && newValue == "Disconnected") {
                    runOnUiThread {
                        webView.removeAllViews();
                        webView.destroy()
                    }
                    finish()
                }
            }
        })

        swipeRefreshLayout.setOnRefreshListener(this);
        webView.setWebViewClient(object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                swipeRefreshLayout.isRefreshing = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefreshLayout.isRefreshing = false
            }
        })
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowContentAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.loadUrl(config.haURL)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val visibility: Int
        if (hasFocus) {
            // Keep screen on
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            //TODO: Test this hides status and action bar
            visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            window.decorView.systemUiVisibility = visibility
        }
    }

    override fun onRefresh() {
        webView.reload()
    }

}