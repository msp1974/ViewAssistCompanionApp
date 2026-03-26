package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.app.AlertDialog
import androidx.webkit.WebViewClientCompat
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VAViewModel
import timber.log.Timber
import java.net.URL
import androidx.core.net.toUri

class CustomWebViewClient(val viewModel: VAViewModel): WebViewClientCompat()  {
    val log = Logger()
    val config = viewModel.config!!
    private val firebase = FirebaseManager.getInstance(config.context)
    private val resources = viewModel.resources!!

    companion object {

        private const val APP_PREFIX = "app://"
        private const val INTENT_PREFIX = "intent:"
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        log.e("Webview render process gone: $detail")
        var reason = FirebaseManager.RENDER_PROCESS_CRASHED
        if (detail?.didCrash() != true) {
            reason = FirebaseManager.RENDER_PROCESS_KILLED
        }
        firebase.logEvent (reason, mapOf("detail" to detail.toString()))
        try {
            view.reload()
        } catch (e: Exception) {
            log.e("Failed to reload webview: $e")
            // Broadcast to activity to handle webview restart
            BroadcastSender.sendBroadcast(config.context, BroadcastSender.WEBVIEW_CRASH)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        url.let {
            try {
                val pm: PackageManager = config.context.packageManager
                val activityContext = config.context.takeIf { it is Activity } ?: return false

                // If the url is our client id then capture the auth code and get an access token
                if (it.contains(AuthUtils.CLIENT_URL)) {
                    val authCode = AuthUtils.getReturnAuthCode(url)
                    if (authCode != "") {
                        // Get access token using auth token
                        val auth = AuthUtils.authoriseWithAuthCode(AuthUtils.getHAUrl(config), authCode, !config.ignoreSSLErrors)
                        if (auth.accessToken == "") {
                            // Not authorised.  Send back to login screen
                            view.loadUrl(AuthUtils.getAuthUrl(AuthUtils.getHAUrl(config)))
                        } else {
                            // Authorised. Load HA default dashboard
                            config.accessToken = auth.accessToken
                            config.refreshToken = auth.refreshToken
                            config.tokenExpiry = auth.expires
                            view.loadUrl(AuthUtils.getURL(AuthUtils.getHAUrl(config)))
                        }
                    }
                } else if (it.startsWith(APP_PREFIX)) {
                    Timber.d("Launching the app")
                    val intent = pm.getLaunchIntentForPackage(
                        it.substringAfter(APP_PREFIX),
                    )
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        activityContext.startActivity(intent)
                    } else {
                        Timber.w("No intent to launch app found")
                    }
                    return true
                } else if (it.startsWith(INTENT_PREFIX)) {
                    Timber.d("Launching the intent")
                    val intent =
                        Intent.parseUri(it, Intent.URI_INTENT_SCHEME)
                    val intentPackage = intent.`package`?.let { it1 ->
                        pm.getLaunchIntentForPackage(
                            it1,
                        )
                    }
                    if (intentPackage == null && !intent.`package`.isNullOrEmpty()) {
                        Timber.w("No app found for intent prefix")
                    } else {
                        activityContext.startActivity(intent)
                    }
                    return true
                } else if (!it.toString().contains(it)) {
                    Timber.d("Launching browser")
                    val browserIntent = Intent(Intent.ACTION_VIEW, it.toUri())
                    activityContext.startActivity(browserIntent)
                    return true
                } else {
                    // Do nothing.
                }
            } catch (e: Exception) {
                Timber.e(e, "Unable to override the URL")
            }
        }
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        Timber.d("Page started: $url")
        setPageLoadingState(PageLoadingStage.STARTED)
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (viewModel.vacaState.value.webViewPageLoadingStage == PageLoadingStage.AUTHORISED) {
            Handler(Looper.getMainLooper()).postDelayed({
                setPageLoadingState(PageLoadingStage.LOADED)
            }, 1000)
            Timber.d("Page finished loading: $url")
        }
        super.onPageFinished(view, url)
    }

    fun setPageLoadingState(stage: PageLoadingStage) {
        viewModel.setWebViewPageLoadingState(stage)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        view.loadUrl("file:///android_asset/web/error.html")
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        log.e("SSL Error: $error")
        if (!config.ignoreSSLErrors) {
            var message = resources.getString(R.string.dialog_message_ssl_generic)
            when (error.primaryError) {
                SslError.SSL_UNTRUSTED -> message =
                    resources.getString(R.string.dialog_message_ssl_untrusted)
                SslError.SSL_EXPIRED -> message =
                    resources.getString(R.string.dialog_message_ssl_expired)
                SslError.SSL_IDMISMATCH -> message =
                    resources.getString(R.string.dialog_message_ssl_mismatch)
                SslError.SSL_NOTYETVALID -> message =
                    resources.getString(R.string.dialog_message_ssl_not_yet_valid)
            }
            message += resources.getString(R.string.dialog_message_ssl_continue)
            val alertDialog = AlertDialog.Builder(view.context)
            alertDialog.apply {
                setTitle(resources.getString(R.string.dialog_title_ssl_error))
                setMessage(message)
                setPositiveButton(resources.getString(R.string.dialog_button_yes)) { _: DialogInterface?, _: Int ->
                    handler.proceed()
                    config.ignoreSSLErrors = true
                }
                setNeutralButton(resources.getString(R.string.dialog_button_always_yes)) { _: DialogInterface?, _: Int ->
                    config.ignoreSSLErrors = true
                    config.alwaysIgnoreSSLErrors = true
                    handler.proceed()
                }
                setNegativeButton(resources.getString(R.string.dialog_button_no)) { _: DialogInterface?, _: Int ->
                    super.onReceivedSslError(view, handler, error)
                    view.loadUrl("file:///android_asset/web/error.html")
                }
            }.create().show()
        } else {
            handler.proceed()
        }
    }

    override fun doUpdateVisitedHistory(
        view: WebView,
        url: String,
        isReload: Boolean,
    ) {
        config.currentPath = URL(url).path
    }


}
