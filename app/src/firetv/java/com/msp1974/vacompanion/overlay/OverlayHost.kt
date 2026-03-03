package com.msp1974.vacompanion.overlay

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.msp1974.vacompanion.utils.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class OverlayHost private constructor(
    private val activity: Activity,
    private val windowManager: WindowManager,
    private val lp: WindowManager.LayoutParams
) {

    var onCollapsed: (() -> Unit)? = null
    var onPickerShown: (() -> Unit)? = null
    private val log = Logger()

    val root: FrameLayout = FrameLayout(activity).apply {
        visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = 16f
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(Color.TRANSPARENT)
            }
        }
    }

    /**
     * Configurable fade timings (default: 2s in, 1s out).
     * Call setOverlayFadeDurations(...) to override at runtime.
     */
    private var overlayFadeInMs: Long = 2000L
    private var overlayFadeOutMs: Long = 1000L

    fun setOverlayFadeDurations(fadeInMs: Long, fadeOutMs: Long) {
        overlayFadeInMs = fadeInMs.coerceAtLeast(0L)
        overlayFadeOutMs = fadeOutMs.coerceAtLeast(0L)
        log.i("OverlayHost: fade configured in=$overlayFadeInMs out=$overlayFadeOutMs")
    }

    /**
     * NOTE: This must remain public (used by OverlayController / PickerController).
     */
    var webViewContainer: View? = null
        set(v) {
            field = v
            v?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                root.addView(
                    it,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                // Start content hidden so first showOverlay() can fade it in.
                it.alpha = 0f
                it.visibility = View.INVISIBLE
                ensureOverlayLayers()
            }
        }

    private val bubbleLabel: TextView = TextView(activity).apply {
        text = "--:--"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        includeFontPadding = false
        background = GradientDrawable().apply {
            cornerRadius = dp(12f)
            setColor(Color.parseColor("#B3000000"))
        }
        val p = dpInt(6f)
        setPadding(p, p, p, p)
        visibility = View.GONE
    }

    private val scrim: View = View(activity).apply {
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.GONE
        alpha = 0f
        isClickable = false
    }
    private val overlayContainer: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        visibility = View.VISIBLE
    }
    private val pickerCard: FrameLayout by lazy {
        FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(28f)
                setColor(Color.parseColor("#CC1A1A1A"))
            }
            val padH = dpInt(20f)
            val padV = dpInt(16f)
            setPadding(padH, padV, padH, padV)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = 18f
        }
    }

    private var bubbleTicker: Runnable? = null

    private var savedWidth: Int = 0
    private var savedHeight: Int = 0
    private var savedGravity: Int = Gravity.TOP or Gravity.END
    private var pickerExpanded: Boolean = false
    private var wasBubbleBeforePicker: Boolean = false

    private var overlayExpanded: Boolean = false
    private var pickerVisible: Boolean = false

    // Track last dashboard overlay request so we can sanity-reapply after display transitions
    private var lastOverlayWidthRatio: Float? = null
    private var lastOverlayHeightRatio: Float? = null
    private var lastOverlayGravity: Int = Gravity.TOP or Gravity.END

    init {
        root.addView(
            bubbleLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        ensureOverlayLayers()
    }

    private fun ensureOverlayLayers() {
        if (scrim.parent == null) {
            root.addView(
                scrim,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        if (overlayContainer.parent == null) {
            root.addView(
                overlayContainer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun dp(px: Float): Float = px * activity.resources.displayMetrics.density
    private fun dpInt(px: Float): Int = dp(px).roundToInt()

    /**
     * Prefer real display metrics from WindowManager over activity.resources.displayMetrics.
     * This avoids stale/incorrect numbers during HDMI / resolution transitions on FireTV.
     */
    private fun getRealSizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /**
     * After certain display transitions, even "real" metrics can settle a beat later.
     * This re-check tightens up sporadic offscreen placement without changing external APIs.
     */
    private fun scheduleOverlayBoundsRecheck() {
        val wr = lastOverlayWidthRatio
        val hr = lastOverlayHeightRatio
        if (wr == null || hr == null) return

        root.removeCallbacks(overlayRecheckRunnable)
        root.postDelayed(overlayRecheckRunnable, 250L)
    }

    private val overlayRecheckRunnable = Runnable {
        if (!overlayExpanded || pickerVisible) return@Runnable

        val wr = lastOverlayWidthRatio ?: return@Runnable
        val hr = lastOverlayHeightRatio ?: return@Runnable
        val (w, h) = getRealSizePx()

        val expectedW = (w * wr).roundToInt()
        val expectedH = (h * hr).roundToInt()

        if (lp.width != expectedW || lp.height != expectedH || lp.gravity != lastOverlayGravity) {
            lp.width = expectedW
            lp.height = expectedH
            lp.gravity = lastOverlayGravity
            lp.x = 0; lp.y = 0
            safelyAttachOrUpdate()
        }
    }

    // ---------- Bubble helpers ----------

    private fun updateTime() {
        bubbleLabel.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        bubbleLabel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        lp.width = bubbleLabel.measuredWidth
        lp.height = bubbleLabel.measuredHeight
        safelyAttachOrUpdate()
    }

    private fun startBubbleTicker() {
        stopBubbleTicker()
        val now = System.currentTimeMillis()
        val delay = 60_000 - (now % 60_000)
        bubbleTicker = object : Runnable {
            override fun run() {
                updateTime()
                root.postDelayed(this, 60_000)
            }
        }
        root.postDelayed(bubbleTicker!!, delay)
    }

    private fun stopBubbleTicker() {
        bubbleTicker?.let { root.removeCallbacks(it) }
        bubbleTicker = null
    }

    fun setBubbleOnline(online: Boolean) {
        (bubbleLabel.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (online) "#B3000000" else "#CCB00000")
        )
    }

    // ---------- Dashboard overlay API ----------

    fun showOverlay(widthRatio: Float, heightRatio: Float, gravity: Int, durationMillis: Long) {
        lastOverlayWidthRatio = widthRatio
        lastOverlayHeightRatio = heightRatio
        lastOverlayGravity = gravity

        val (w, h) = getRealSizePx()
        lp.width = (w * widthRatio).roundToInt()
        lp.height = (h * heightRatio).roundToInt()
        lp.gravity = gravity
        lp.x = 0; lp.y = 0

        // Prepare content for fade-in (fade the actual overlay content, not the window).
        webViewContainer?.apply {
            clearAnimation()
            alpha = 0f
            visibility = View.VISIBLE
            bringToFront()
        }

        bubbleLabel.visibility = View.GONE
        stopBubbleTicker()

        safelyAttachOrUpdate()

        // Make root visible without window-level fade to avoid "window" feel
        root.visibility = View.VISIBLE

        overlayExpanded = true

        // Cancel pending hides and schedule a new one if requested
        root.removeCallbacks(hideRunnable)

        // if duration is 0, the overlay persists until it is replaced by something else (so music can be persistent)
        val animator = webViewContainer?.animate()
            ?.alpha(1f)
            ?.setDuration(overlayFadeInMs)

        if (durationMillis > 0L) {
            animator?.withEndAction {
                root.postDelayed(hideRunnable, durationMillis)
            }
        }

        animator?.start()

        // Defensive re-check for transient HDMI/resolution moments.
        scheduleOverlayBoundsRecheck()
    }

    /** Apply size/gravity only (used to pre-size before revealing the WebView). */
    fun applyOverlayBounds(widthRatio: Float, heightRatio: Float, gravity: Int) {
        lastOverlayWidthRatio = widthRatio
        lastOverlayHeightRatio = heightRatio
        lastOverlayGravity = gravity

        val (w, h) = getRealSizePx()
        lp.width = (w * widthRatio).roundToInt()
        lp.height = (h * heightRatio).roundToInt()
        lp.gravity = gravity
        lp.x = 0; lp.y = 0
        safelyAttachOrUpdate()

        scheduleOverlayBoundsRecheck()
    }

    fun shrinkToBubble() {
        webViewContainer?.alpha = 0f
        webViewContainer?.visibility = View.VISIBLE

        bubbleLabel.visibility = View.VISIBLE
        bubbleLabel.bringToFront()

        // Set root VISIBLE *before* attaching to WindowManager so the
        // compositor sees a visible surface from the start.  On the FireTV
        // Cube the GONE→VISIBLE transition after addView() is not picked up
        // without an extra updateViewLayout(), which left the bubble clock
        // invisible at boot.
        root.visibility = View.VISIBLE

        lp.width = dpInt(110f)
        lp.height = dpInt(32f)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = 0; lp.y = 0
        safelyAttachOrUpdate()
        updateTime()
        startBubbleTicker()

        overlayExpanded = false

        // notify controller after collapse (webview is invisible)
        try { onCollapsed?.invoke() } catch (_: Throwable) {}
    }

    /**
     * Fade the overlay content out, then collapse to bubble.
     */
    fun hideOverlay() {
        if (!overlayExpanded) {
            shrinkToBubble()
            return
        }
        val target = webViewContainer
        if (target == null) {
            shrinkToBubble()
            return
        }
        target.animate()
            .alpha(0f)
            .setDuration(overlayFadeOutMs)
            .withEndAction { shrinkToBubble() }
            .start()
    }

    // Auto-hide uses the fade-out path
    private val hideRunnable = Runnable { hideOverlay() }

    private fun safelyAttachOrUpdate() {
        try {
            if (root.windowToken == null) {
                windowManager.addView(root, lp)
            } else {
                windowManager.updateViewLayout(root, lp)
            }
        } catch (_: Exception) {
            try { windowManager.addView(root, lp) } catch (_: Exception) {}
            try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
    }

    // ---------- Picker API (lozenge) ----------

    fun showPicker(view: View) {
        // Cancel any pending dashboard hide timer so it can't fire while the picker is up.
        root.removeCallbacks(hideRunnable)

        // Hide bubble first so it never “jumps” during resizing
        if (bubbleLabel.visibility == View.VISIBLE) {
            stopBubbleTicker()
            bubbleLabel.visibility = View.GONE
        }

        // make underlying web content invisible while picker is shown
        webViewContainer?.alpha = 0f
        try { onPickerShown?.invoke() } catch (_: Throwable) {}

        if (!pickerExpanded) {
            wasBubbleBeforePicker = !overlayExpanded
            savedWidth = lp.width
            savedHeight = lp.height
            savedGravity = lp.gravity
            pickerExpanded = true

            // Expand window to screen so the lozenge is centered comfortably
            val (w, h) = getRealSizePx()
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 0; lp.y = 0
            safelyAttachOrUpdate()
            root.visibility = View.VISIBLE
        }

        // Hide webview under picker
        webViewContainer?.visibility = View.INVISIBLE
        webViewContainer?.alpha = 0f
        overlayExpanded = false

        val (screenW, _) = getRealSizePx()

        if (pickerCard.parent != overlayContainer) {
            overlayContainer.addView(
                pickerCard,
                FrameLayout.LayoutParams(
                    (screenW * 0.80f).roundToInt(),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        if (view.parent != pickerCard) {
            (view.parent as? ViewGroup)?.removeView(view)
            pickerCard.removeAllViews()
            pickerCard.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        if (pickerCard.alpha < 1f) {
            pickerCard.scaleX = 0.95f
            pickerCard.scaleY = 0.95f
            pickerCard.alpha = 0f
            pickerCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        }
        pickerVisible = true
    }

    /**
     * If restoreToWeb=true we KEEP whatever bounds are currently set (possibly pre-sized)
     * and reveal the webview immediately. No bubble, no full-screen flash.
     *
     * If forceBubble=true and the picker was opened over a dashboard, return to bubble instead
     * of resurrecting the underlying dashboard.
     */
    fun hidePicker(view: View, restoreToWeb: Boolean = false, forceBubble: Boolean = false) {
        pickerCard.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(120).withEndAction {
            try { pickerCard.removeAllViews() } catch (_: Exception) {}
            try { overlayContainer.removeView(pickerCard) } catch (_: Exception) {}
        }.start()

        pickerVisible = false

        if (!pickerExpanded) {
            if (overlayExpanded) bubbleLabel.visibility = View.GONE
            return
        }

        if (restoreToWeb) {
            // Do NOT touch lp.* here — bounds may have been pre-sized for the destination.
            pickerExpanded = false
            bubbleLabel.visibility = View.GONE
            stopBubbleTicker()
            webViewContainer?.visibility = View.VISIBLE
            webViewContainer?.alpha = 1f
            overlayExpanded = true
            safelyAttachOrUpdate()
            wasBubbleBeforePicker = false
            return
        }

        // Normal restore path, but allow forcing bubble (idle/back)
        pickerExpanded = false
        if (forceBubble || wasBubbleBeforePicker) {
            shrinkToBubble()
            wasBubbleBeforePicker = false
            return
        }

        // Restore previous dashboard bounds
        lp.width = savedWidth
        lp.height = savedHeight
        lp.gravity = savedGravity
        safelyAttachOrUpdate()

        webViewContainer?.visibility = View.VISIBLE
        webViewContainer?.alpha = 1f
        overlayExpanded = true
    }

    fun destroy() {
        stopBubbleTicker()
        try { windowManager.removeView(root) } catch (_: Exception) {}
    }

    fun isOverlayExpanded(): Boolean = overlayExpanded
    fun isPickerVisible(): Boolean = pickerVisible

    companion object {
        private val log = Logger()

        fun create(activity: Activity): OverlayHost? {
            if (!Settings.canDrawOverlays(activity)) {
                log.w("OverlayHost: overlay permission missing – host will not be created")
                return null
            }

            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

            val lp = WindowManager.LayoutParams(
                0, 0, type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                @Suppress("DEPRECATION")
                softInputMode =
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            }

            return OverlayHost(activity, wm, lp)
        }
    }
}
