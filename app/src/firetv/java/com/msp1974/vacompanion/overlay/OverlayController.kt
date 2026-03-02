// app/src/main/java/com/msp1974/vacompanion/overlay/OverlayController.kt
package com.msp1974.vacompanion.overlay

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.ble.BleRemoteService
import com.msp1974.vacompanion.picker.PickerController
import com.msp1974.vacompanion.utils.Logger
import java.lang.ref.WeakReference
import java.util.Locale

class OverlayController private constructor(
    private val activity: Activity,
    private val webView: WebView,
    private val host: OverlayHost?
) {
    private val log = Logger()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var installedFlag: Boolean = true
    val isInstalled: Boolean
        get() = installedFlag

    // ---- Picker input gate ----
    private val ACTION_SYSTEM_READY = "com.msp1974.vacompanion.ACTION_SYSTEM_READY"
    private var systemReadySent = false
    private fun sendSystemReadyOnce() {
        if (systemReadySent) return
        systemReadySent = true
        LocalBroadcastManager.getInstance(activity)
            .sendBroadcast(Intent(ACTION_SYSTEM_READY))
        log.i("SystemReady: broadcast sent")
    }

    // --- Connect suppression ---
    private enum class ConnectPhase { CONNECTING, RUNNING }
    private var connectPhase: ConnectPhase = ConnectPhase.CONNECTING
    private var connectDefaultSeen = false
    private var connectClockSeen = false
    private val SUPPRESS_CONNECTING = true

    private fun resetConnectSuppression() {
        connectPhase = ConnectPhase.CONNECTING
        connectDefaultSeen = false
        connectClockSeen = false
        host?.setBubbleOnline(false)
        systemReadySent = false
        log.i("OverlayController: connect phase reset → CONNECTING")
    }

    // --- JS bridge + injection with debounce ---
    private inner class NavBridge {
        @JavascriptInterface
        fun onPathChange(hrefRaw: String?) {
            if (hrefRaw.isNullOrBlank()) return
            log.d("JS NavBridge → $hrefRaw")
            main.post { handlePossibleInAppNavigation(hrefRaw, "js-nav-hook") }
        }
    }

    private var bootstrapTicker: Runnable? = null
    private fun startJsBootstrapTicker() {
        stopJsBootstrapTicker()
        var attempts = 0
        bootstrapTicker = object : Runnable {
            override fun run() {
                attempts++
                injectSpaNavigationHook()
                // also keep kiosk CSS in place in case HA re-renders
                forceHideHaChrome()
                main.postDelayed(this, if (attempts < 20) 500 else 5000)
            }
        }
        main.post(bootstrapTicker!!)
    }
    private fun stopJsBootstrapTicker() {
        bootstrapTicker?.let { main.removeCallbacks(it) }
        bootstrapTicker = null
    }

    private fun injectSpaNavigationHook() {
        val js = """
            (function() {
              try {
                if (window.__vaNavHooked) { window.__vaLastHref = location.href; return; }
                window.__vaNavHooked = true;
                window.__vaLastHref = location.href;
                window.__vaDebouncing = false;
                function debouncedNotify() {
                  if (window.__vaDebouncing) return;
                  window.__vaDebouncing = true;
                  setTimeout(function() {
                    try {
                      var href = location.href;
                      if (href !== window.__vaLastHref) {
                        window.__vaLastHref = href;
                        if (window.ViewAssistNav && ViewAssistNav.onPathChange) {
                          ViewAssistNav.onPathChange(href);
                        }
                      }
                    } catch (e) {}
                    window.__vaDebouncing = false;
                  }, 250);
                }
                var push = history.pushState;
                history.pushState = function() { var r = push.apply(this, arguments); debouncedNotify(); return r; };
                var replace = history.replaceState;
                history.replaceState = function() { var r = replace.apply(this, arguments); debouncedNotify(); return r; };
                window.addEventListener('popstate', debouncedNotify);
                window.addEventListener('hashchange', debouncedNotify);
                if (!window.__vaHrefWatch) {
                  window.__vaHrefWatch = setInterval(function() {
                    try { if (location.href !== window.__vaLastHref) debouncedNotify(); } catch (e) {}
                  }, 500);
                }
                if (window.ViewAssistNav && ViewAssistNav.onPathChange) {
                  ViewAssistNav.onPathChange(location.href);
                }
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // --- Keep HA header/sidebar hidden (force kiosk) ---
    private fun forceHideHaChrome() {
        val js = """
            (function(){
              try {
                // One-time stylesheet with aggressive selectors
                var id = 'va-kiosk-style';
                var s = document.getElementById(id);
                if (!s) {
                  s = document.createElement('style');
                  s.id = id;
                  s.textContent = `
                    :root { --app-drawer-width: 0px !important; }
                    ha-sidebar, app-drawer, ha-drawer, ha-sidebar * { display: none !important; width: 0 !important; }
                    app-header, app-toolbar, ha-tabs, .header, .toolbar { display: none !important; height: 0 !important; }
                    #view, hui-view, hui-panel-view, hui-panel-lovelace { padding-top: 0 !important; }
                    body { margin-left: 0 !important; }
                    ha-app-layout app-header, ha-app-layout app-toolbar { display: none !important; }
                    ha-app-layout ha-sidebar { display: none !important; width: 0 !important; }
                    mwc-drawer, paper-drawer-panel { display: none !important; }
                    .main-title, ha-menu-button { display: none !important; }
                  `;
                  document.head.appendChild(s);
                }
                document.documentElement.style.setProperty('--app-drawer-width','0px','important');
                const elementsToHide = [
                  'ha-sidebar', 'app-drawer', 'ha-drawer', 'app-header', 'app-toolbar', 
                  'ha-tabs', 'mwc-drawer', 'paper-drawer-panel', 'ha-menu-button'
                ];
                elementsToHide.forEach(function(selector) {
                  try {
                    const elements = document.querySelectorAll(selector);
                    elements.forEach(function(el) {
                      el.style.display = 'none';
                      el.style.width = '0';
                      el.style.height = '0';
                      el.style.visibility = 'hidden';
                    });
                  } catch(e) {}
                });
                if (!window.__vaKioskObs) {
                  window.__vaKioskObs = new MutationObserver(function(){
                    try {
                      document.documentElement.style.setProperty('--app-drawer-width','0px','important');
                      elementsToHide.forEach(function(selector) {
                        try {
                          const elements = document.querySelectorAll(selector);
                          elements.forEach(function(el) {
                            el.style.display = 'none';
                            el.style.width = '0';
                            el.style.height = '0';
                            el.style.visibility = 'hidden';
                          });
                        } catch(e) {}
                      });
                    } catch(e){}
                  });
                  window.__vaKioskObs.observe(document.documentElement, {childList:true, subtree:true});
                }
              } catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun forceHideHaChromeDelayed() {
        webView.post {
            forceHideHaChrome()
            main.postDelayed({ forceHideHaChrome() }, 300)
            main.postDelayed({ forceHideHaChrome() }, 1200)
        }
    }

    // Extra-aggressive timing when picker drives nav
    private fun forceHideHaChromeForPicker() {
        webView.post {
            forceHideHaChrome()
            main.postDelayed({ forceHideHaChrome() }, 100)
            main.postDelayed({ forceHideHaChrome() }, 300)
            main.postDelayed({ forceHideHaChrome() }, 600)
            main.postDelayed({ forceHideHaChrome() }, 1200)
            main.postDelayed({ forceHideHaChrome() }, 2000)
        }
    }

    /** (1) Public wrapper MainActivity can call without touching private members. */
    fun markOnlineAndSystemReady() {
        host?.setBubbleOnline(true)
        sendSystemReadyOnce()
    }

    // --- Picker + BLE ---------------------------------------------------------------
    private var picker: PickerController? = null

    private fun startBleRemote() {
        try {
            activity.startService(Intent(activity, BleRemoteService::class.java))
            log.i("BleRemoteService started")
        } catch (e: Exception) {
            log.e("Failed to start BleRemoteService: ${e.message}")
        }
    }

    /** JS push to a dashboard path (used by picker select). Make this behave IDENTICAL to voice. */
    private fun navigateToPath(path: String) {
        val key = pathToKey(path)
        if (key != "placeholder") {
            val p = profileFor(key)
            host?.applyOverlayBounds(p.widthRatio, p.heightRatio, p.gravity)
            host?.showOverlay(p.widthRatio, p.heightRatio, p.gravity, p.durationMs)
        }

        // Apply kiosk chrome hiding before navigation
        forceHideHaChrome()

        val js = """
            (function(){
                try {
                    const target = "$path";
                    if (window.history && window.history.pushState) {
                        if (location.pathname !== target) {
                            history.pushState({}, '', target);
                        }
                        if (window.dispatchEvent) {
                            window.dispatchEvent(new CustomEvent('location-changed', {
                                detail: { pathname: target }
                            }));
                            window.dispatchEvent(new Event('popstate'));
                        }
                        if (window.ViewAssistNav && ViewAssistNav.onPathChange) {
                            ViewAssistNav.onPathChange(location.href);
                        }
                    } else {
                        location.href = target;
                    }
                } catch(e) { 
                    console.error('Navigation error:', e);
                    location.href = "$path"; 
                }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(js, null)
        }

        // Reinforce kiosk CSS
        main.postDelayed({ forceHideHaChrome() }, 150)
        main.postDelayed({ forceHideHaChrome() }, 300)
        main.postDelayed({ forceHideHaChrome() }, 600)

        log.i("Picker navigation → $path")
    }

    // ---------- Profiles ----------
    private data class Profile(
        val widthRatio: Float,
        val heightRatio: Float,
        val gravity: Int,
        val durationMs: Long
    )

    private fun profileFor(key: String): Profile = when (key) {
        "weather"    -> Profile(1.00f, 0.65f, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 10_000L)
        "camera"     -> Profile(0.90f, 0.90f, Gravity.CENTER,                                30_000L)
        "newmusic"   -> Profile(1.00f, 1.00f, Gravity.CENTER,                               0L) // Persistent, until replaced
        "music"      -> Profile(1.00f, 1.00f, Gravity.CENTER,                               0L) // Persistent, until replaced
        "thermostat" -> Profile(0.25f, 0.25f, Gravity.BOTTOM or Gravity.END,                10_000L)
        "locate"     -> Profile(0.75f, 0.75f, Gravity.CENTER,                                15_000L)
        "find"       -> Profile(0.75f, 0.75f, Gravity.CENTER,                                0L) // Persistent, until cancelled by select
        "clock"      -> Profile(0.26f, 0.18f, Gravity.TOP or Gravity.END,                      100L)
        else         -> Profile(0.50f, 0.50f, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,   10_000L)
    }

    private fun preSizeForPath(path: String) {
        val key = pathToKey(path)
        val p = profileFor(key)
        host?.applyOverlayBounds(p.widthRatio, p.heightRatio, p.gravity)
    }

    // --- Public API ---
    fun destroy() {
        stopJsBootstrapTicker()
        try { host?.destroy() } catch (_: Exception) {}
        picker?.dispose()
        picker = null
        installedFlag = false            // <-- add this line
        if (instance?.get() === this) instance = null
        log.i("OverlayController: destroyed")
    }


    companion object {
        // Singleton guard
        private var instance: WeakReference<OverlayController>? = null

        fun install(activity: Activity, webView: WebView): OverlayController {
            instance?.get()?.let {
                it.log.w("OverlayController.install called again – reusing existing instance")
                return it
            }

            val host = OverlayHost.create(activity)
            val oc = OverlayController(activity, webView, host)
            instance = WeakReference(oc)

            //host?.onCollapsed   = { oc.navigateToPath("/view-assist/placeholder") }
            //host?.onPickerShown = { oc.navigateToPath("/view-assist/placeholder") }

            // Hand the WebView to the host container
            host?.webViewContainer = webView

            // ✅ Expose NavBridge to JS so injectSpaNavigationHook() can call it
            webView.addJavascriptInterface(oc.NavBridge(), "ViewAssistNav")

            // Create picker (unchanged)
            if (host != null) {
                oc.picker = PickerController(
                    activity = activity,
                    host = host,
                    navigateTo = { path -> oc.navigateToPath(path) },
                    preSizeForPath = { path -> oc.preSizeForPath(path) }
                )
            }

            // Fire “system ready” once picker receivers exist
            oc.sendSystemReadyOnce()

            // Start the repeated JS injection (already present)
            oc.startJsBootstrapTicker()

            // Start BLE remote (already present)
            oc.startBleRemote()

            // Seed overlay state from the current URL (optional but helpful)
            oc.considerCurrentUrl(webView.url)

            return oc
        }
    }

    // --- Navigation handling --------------------------------------------------------
    private fun handlePossibleInAppNavigation(href: String, source: String) {
        if (href.endsWith("/view-assist") || href.endsWith("/viewassist")) {
            resetConnectSuppression()
        }
        if (!href.contains("/viewassist/") && !href.contains("/view-assist/")) return

        val profile = parseViewType(href)

        // Ignore auto /clock while picker is visible
        if (profile == "clock" && (host?.isPickerVisible() == true)) {
            log.i("Clock nav ignored because picker is visible")
            return
        }

        if (profile == "clock") {
            log.i("Clock nav ignored")
            host?.shrinkToBubble()
            return
        }

        if (!shouldShowForProfile(profile)) return

        log.d("Navigation detected ($source): $href → profile=$profile")
        showProfile(profile)

        // Apply chrome hiding for voice navigation (picker already applied it)
        webView.evaluateJavascript("window.__vaPickerNavigation || false") { result ->
            val isPickerNavigation = result == "true"
            webView.evaluateJavascript("window.__vaPickerNavigation = false", null)

            if (isPickerNavigation) {
                log.d("Picker navigation detected - chrome hiding already applied")
                forceHideHaChromeForPicker()
            } else {
                log.d("Voice navigation detected - applying standard chrome hiding")
                forceHideHaChromeDelayed()
            }
        }
    }

    private fun parseViewType(url: String): String {
        val u = url.lowercase(Locale.ROOT)
        return when {
            u.contains("/viewassist/weather")     || u.contains("/view-assist/weather")      -> "weather"
            u.contains("/viewassist/clock")       || u.contains("/view-assist/clock")        -> "clock"
            u.contains("/viewassist/camera")      || u.contains("/view-assist/camera")       -> "camera"
            u.contains("/viewassist/thermostat")  || u.contains("/view-assist/thermostat")   -> "thermostat"
            u.contains("/viewassist/newmusic")    || u.contains("/view-assist/newmusic")     -> "newmusic"
            u.contains("/viewassist/music")       || u.contains("/view-assist/music")        -> "music"
            u.contains("/viewassist/locate")      || u.contains("/view-assist/locate")       -> "locate"
            u.contains("/viewassist/find")        || u.contains("/view-assist/find")         -> "find"
            u.contains("/viewassist/placeholder") || u.contains("/view-assist/placeholder")  -> "placeholder"
            u.contains("/viewassist/")            || u.contains("/view-assist/")             -> "default"
            else -> "default"
        }
    }

    private fun shouldShowForProfile(profile: String): Boolean {
        if (profile == "placeholder") {
            log.i("Overlay suppressed for placeholder")
            return false
        }

        if (connectPhase == ConnectPhase.CONNECTING) {
            when (profile) {
                "default" -> {
                    if (!connectDefaultSeen) {
                        connectDefaultSeen = true
                        log.i("*** CONNECTING WEBVIEW SCREEN *** default")
                        if (SUPPRESS_CONNECTING) {
                            log.i("*** CONNECTING overlay SUPPRESSED: default")
                            return false
                        }
                    }
                }
                "clock" -> {
                    if (!connectClockSeen) {
                        connectClockSeen = true
                        log.i("*** CONNECTING WEBVIEW SCREEN *** clock")
                        connectPhase = ConnectPhase.RUNNING
                        host?.setBubbleOnline(true)
                        if (SUPPRESS_CONNECTING) {
                            log.i("*** CONNECTING overlay SUPPRESSED: clock")
                            host?.shrinkToBubble()
                            sendSystemReadyOnce()
                        } else {
                            sendSystemReadyOnce()
                        }
                    } else {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun showProfile(profile: String) {
        val p = profileFor(profile)
        host?.showOverlay(p.widthRatio, p.heightRatio, p.gravity, p.durationMs)
    }

    private fun pathToKey(path: String): String {
        val p = path.lowercase(Locale.ROOT)
        return when {
            p.contains("/view-assist/camera")      -> "camera"
            p.contains("/view-assist/weather")     -> "weather"
            p.contains("/view-assist/music")       -> "music"
            p.contains("/view-assist/clock")       -> "clock"
            p.contains("/view-assist/thermostat")  -> "thermostat"
            p.contains("/view-assist/locate")      -> "locate"
            p.contains("/view-assist/find")        -> "find"
            p.contains("/view-assist/placeholder") -> "placeholder"
            else -> "default"
        }
    }

    /** Seed overlay state from the current URL if hooks haven't fired yet. */
    fun considerCurrentUrl(url: String?) {
        if (url.isNullOrBlank()) return
        handlePossibleInAppNavigation(url, source = "native-current")
    }
}
