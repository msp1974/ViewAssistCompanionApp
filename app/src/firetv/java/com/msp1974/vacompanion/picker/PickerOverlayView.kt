package com.msp1974.vacompanion.picker

import com.msp1974.vacompanion.R
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.msp1974.vacompanion.utils.Logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PickerOverlayView(context: Context) : FrameLayout(context) {
    private val log = Logger()

    companion object {
        /** Default color used for attribute text. */
        const val DEFAULT_ATTR_COLOR: Int = 0xFFDDDDDD.toInt()
    }

    private val selectedTitleColor by lazy { context.getColor(R.color.blue) }

    private val visibleSlots = 4
    private val bufferSlots = 1
    private val totalSlots = visibleSlots + bufferSlots
    private val slotHMarginPx = (8 * resources.displayMetrics.density).toInt()

    private val track = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private var configured = false
    private var slotWidth = 0

    private var items: List<Preset> = emptyList()
    private var selected = 0
    private var windowStart = 0
    private val focusPos = 1

    private val iconDrawables = hashMapOf<Int, Drawable?>()
    private val titleOverrides = hashMapOf<Int, String>()
    private val attrLine1Overrides = hashMapOf<Int, String>()
    private val attrLine2Overrides = hashMapOf<Int, String>()
    private val attrLine1Colors = hashMapOf<Int, Int>()
    private val attrLine2Colors = hashMapOf<Int, Int>()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        track.addView(row)
        isClickable = true; isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (width > 0 && !configured) { configureSlots(); viewTreeObserver.removeOnGlobalLayoutListener(this) }
            }
        })
    }

    private fun configureSlots() {
        configured = true
        val innerW = width - paddingLeft - paddingRight
        val totalMargins = slotHMarginPx * 2 * visibleSlots
        slotWidth = ((innerW - totalMargins) / max(1, visibleSlots)).coerceAtLeast(1)
        row.removeAllViews()
        repeat(totalSlots) { row.addView(makeSlot()) }
        bindWindow()
        highlight()
    }

    private fun makeSlot(): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LayoutParams(slotWidth, LayoutParams.WRAP_CONTENT).apply {
                setMargins(slotHMarginPx, slotHMarginPx, slotHMarginPx, slotHMarginPx)
            }
        }
        val maxIconH = (resources.displayMetrics.heightPixels * 0.23f).toInt()
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(slotWidth, maxIconH)
            alpha = 0.6f; scaleX = 0.85f; scaleY = 0.85f
        }
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            textSize = 16f
            alpha = 0.7f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (resources.displayMetrics.density * 8).toInt() }
        }
        val attr1 = TextView(context).apply {
            setTextColor(DEFAULT_ATTR_COLOR); maxLines = 1; textSize = 12f; alpha = 0.85f
            gravity = Gravity.CENTER
            visibility = INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (resources.displayMetrics.density * 2).toInt() }
        }
        val attr2 = TextView(context).apply {
            setTextColor(DEFAULT_ATTR_COLOR); maxLines = 1; textSize = 12f; alpha = 0.85f
            gravity = Gravity.CENTER
            visibility = INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (resources.displayMetrics.density * 2).toInt() }
        }
        col.addView(iv); col.addView(tv); col.addView(attr1); col.addView(attr2)
        return col
    }

    fun resetData() {
        iconDrawables.clear()
        titleOverrides.clear()
        attrLine1Overrides.clear()
        attrLine2Overrides.clear()
        attrLine1Colors.clear()
        attrLine2Colors.clear()
        selected = 0
        windowStart = 0
    }

    fun setItems(presets: List<Preset>) {
        items = presets
        val size = max(1, items.size)
        selected = 0
        windowStart = ((selected - focusPos) % size + size) % size
        if (configured) { bindWindow(); highlight() }
    }

    fun show() {
        ensureConfiguredThen {
            isVisible = true
            animate().alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            highlight()
        }
    }

    fun hide(onEnd: (() -> Unit)? = null) {
        animate().alpha(0f).setDuration(120).withEndAction { isVisible = false; onEnd?.invoke() }.start()
    }

    fun currentIndex(): Int = selected

    fun slideBy(dir: Int, count: Int) {
        if (!configured || slotWidth == 0 || items.isEmpty()) return
        val steps = abs(count)
        fun step(rem: Int) {
            if (rem <= 0) { highlight(); return }
            if (dir > 0) animateNext { step(rem - 1) } else animatePrev { step(rem - 1) }
        }
        step(steps)
    }

    private fun animateNext(onDone: () -> Unit) {
        if (!configured || row.childCount == 0) { onDone(); return }
        row.animate().translationX(-slotWidth.toFloat()).setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                windowStart = (windowStart + 1) % items.size
                selected = (selected + 1) % items.size
                row.translationX = 0f
                bindWindow()
                highlight()
                onDone()
            }.start()
    }

    private fun animatePrev(onDone: () -> Unit) {
        if (!configured || row.childCount == 0) { onDone(); return }
        windowStart = (windowStart - 1 + items.size) % items.size
        selected = (selected - 1 + items.size) % items.size
        bindWindow()
        row.translationX = -slotWidth.toFloat()
        row.animate().translationX(0f).setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { highlight(); onDone() }
            .start()
    }

    fun setSelectedIndex(index: Int) {
        if (items.isEmpty()) return
        val size = items.size
        var idx = index % size; if (idx < 0) idx += size
        if (idx == selected) { highlight(); return }
        var steps = idx - selected
        if (steps > size / 2) steps -= size
        if (steps < -size / 2) steps += size
        slideBy(if (steps > 0) +1 else -1, abs(steps))
    }

    fun selectedItem(): Preset? = items.getOrNull(selected)

    private fun bindWindow() {
        if (!configured || items.isEmpty() || row.childCount < totalSlots) return
        for (slot in 0 until totalSlots) {
            val absIndex = (windowStart + slot) % items.size
            val col = row.getChildAt(slot) as? LinearLayout ?: continue
            val iv = col.getChildAt(0) as? ImageView ?: continue
            val tv = col.getChildAt(1) as? TextView ?: continue
            val a1 = col.getChildAt(2) as? TextView
            val a2 = col.getChildAt(3) as? TextView

            val p = items[absIndex]
            tv.text = titleOverrides[absIndex] ?: p.title
            iconDrawables[absIndex]?.let { iv.setImageDrawable(it) }

            val l1 = attrLine1Overrides[absIndex]
            val l2 = attrLine2Overrides[absIndex]

            if (l1.isNullOrBlank()) { a1?.visibility = INVISIBLE; a1?.text = "" }
            else {
                a1?.visibility = VISIBLE
                a1?.text = l1
                a1?.setTextColor(attrLine1Colors[absIndex] ?: DEFAULT_ATTR_COLOR)
            }

            if (l2.isNullOrBlank()) { a2?.visibility = INVISIBLE; a2?.text = "" }
            else {
                a2?.visibility = VISIBLE
                a2?.text = l2
                a2?.setTextColor(attrLine2Colors[absIndex] ?: DEFAULT_ATTR_COLOR)
            }
        }
    }

    private fun highlight() {
        if (!configured || row.childCount < visibleSlots) return
        val selLocal = (selected - windowStart + items.size) % items.size
        val visible = min(visibleSlots, row.childCount)
        for (i in 0 until visible) {
            val col = row.getChildAt(i) as? LinearLayout ?: continue
            val iv = col.getChildAt(0) as? ImageView ?: continue
            val tv = col.getChildAt(1) as? TextView ?: continue
            val a1 = col.getChildAt(2) as? TextView
            val a2 = col.getChildAt(3) as? TextView
            val focused = i == selLocal
            val targetScale = if (focused) 1.0f else 0.85f
            val targetAlpha = if (focused) 1.0f else 0.6f
            val textAlpha  = if (focused) 1.0f else 0.7f
            val attrAlpha  = if (focused) 0.95f else 0.75f

            tv.setTextColor(if (focused) selectedTitleColor  else Color.WHITE)
            iv.setColorFilter(if (focused) selectedTitleColor  else Color.WHITE)

            iv.animate().cancel(); tv.animate().cancel()
            iv.animate().scaleX(targetScale).scaleY(targetScale).alpha(targetAlpha).setDuration(140)
                .setInterpolator(DecelerateInterpolator()).start()
            tv.animate().alpha(textAlpha).setDuration(140).start()
            a1?.animate()?.alpha(attrAlpha)?.setDuration(140)?.start()
            a2?.animate()?.alpha(attrAlpha)?.setDuration(140)?.start()
        }
        if (row.childCount > visibleSlots) {
            val col = row.getChildAt(visibleSlots) as? LinearLayout
            (col?.getChildAt(0) as? ImageView)?.alpha = 0f
            (col?.getChildAt(1) as? TextView)?.alpha = 0f
            (col?.getChildAt(2) as? TextView)?.alpha = 0f
            (col?.getChildAt(3) as? TextView)?.alpha = 0f
        }
    }

    fun setIconDrawable(index: Int, d: Drawable) {
        iconDrawables[index] = d
        if (!configured || row.childCount == 0 || items.isEmpty()) return
        for (slot in 0 until totalSlots) {
            val absIndex = (windowStart + slot) % items.size
            if (absIndex == index) {
                val col = row.getChildAt(slot) as? LinearLayout ?: continue
                val iv = col.getChildAt(0) as? ImageView ?: continue
                iv.setImageDrawable(d)
            }
        }
    }

    fun setTitle(index: Int, title: String) {
        titleOverrides[index] = title
        if (!configured || row.childCount == 0 || items.isEmpty()) return
        for (slot in 0 until totalSlots) {
            val absIndex = (windowStart + slot) % items.size
            if (absIndex == index) {
                val col = row.getChildAt(slot) as? LinearLayout ?: continue
                val tv = col.getChildAt(1) as? TextView ?: continue
                tv.text = title
            }
        }
    }

    fun setAttributes(index: Int, lines: List<String>) {
        val l1 = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ""
        val l2 = lines.getOrNull(1)?.takeIf { it.isNotBlank() } ?: ""
        if (l1.isBlank()) { attrLine1Overrides.remove(index); attrLine1Colors.remove(index) } else { attrLine1Overrides[index] = l1 }
        if (l2.isBlank()) { attrLine2Overrides.remove(index); attrLine2Colors.remove(index) } else { attrLine2Overrides[index] = l2 }
        if (!configured || row.childCount == 0 || items.isEmpty()) return
        for (slot in 0 until totalSlots) {
            val absIndex = (windowStart + slot) % items.size
            if (absIndex == index) {
                val col = row.getChildAt(slot) as? LinearLayout ?: continue
                val a1 = col.getChildAt(2) as? TextView
                val a2 = col.getChildAt(3) as? TextView
                if (l1.isBlank()) { a1?.visibility = INVISIBLE; a1?.text = "" } else {
                    a1?.visibility = VISIBLE; a1?.text = l1; a1?.setTextColor(attrLine1Colors[index] ?: DEFAULT_ATTR_COLOR)
                }
                if (l2.isBlank()) { a2?.visibility = INVISIBLE; a2?.text = "" } else {
                    a2?.visibility = VISIBLE; a2?.text = l2; a2?.setTextColor(attrLine2Colors[index] ?: DEFAULT_ATTR_COLOR)
                }
            }
        }
    }

    /**
     * Set the color for a specific attribute line (1 or 2). Pass DEFAULT_ATTR_COLOR to reset.
     */
    fun setAttributeColor(index: Int, line: Int, color: Int) {
        when (line) {
            1 -> if (color == DEFAULT_ATTR_COLOR) attrLine1Colors.remove(index) else attrLine1Colors[index] = color
            2 -> if (color == DEFAULT_ATTR_COLOR) attrLine2Colors.remove(index) else attrLine2Colors[index] = color
            else -> return
        }
        if (!configured || row.childCount == 0 || items.isEmpty()) return
        for (slot in 0 until totalSlots) {
            val absIndex = (windowStart + slot) % items.size
            if (absIndex == index) {
                val col = row.getChildAt(slot) as? LinearLayout ?: continue
                val a1 = col.getChildAt(2) as? TextView
                val a2 = col.getChildAt(3) as? TextView
                if (line == 1) a1?.setTextColor(attrLine1Colors[index] ?: DEFAULT_ATTR_COLOR)
                if (line == 2) a2?.setTextColor(attrLine2Colors[index] ?: DEFAULT_ATTR_COLOR)
            }
        }
    }

    /**
     * Selection visual feedback: pulse the focused icon.
     * Each pulse = zoom to [zoomScale] then back to 1.0; performed [times] times.
     */
    fun pulseSelectedIcon(times: Int = 2, zoomScale: Float = 1.20f, segmentMs: Long = 500L) {
        if (!configured || items.isEmpty() || row.childCount == 0) return
        val selLocal = (selected - windowStart + items.size) % items.size
        val col = row.getChildAt(selLocal) as? LinearLayout ?: return
        val iv = col.getChildAt(0) as? ImageView ?: return

        iv.animate().cancel()
        fun doPulse(remaining: Int) {
            if (remaining <= 0) return
            iv.animate()
                .scaleX(zoomScale).scaleY(zoomScale)
                .setDuration(segmentMs)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    iv.animate()
                        .scaleX(1.0f).scaleY(1.0f)
                        .setDuration(segmentMs)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { doPulse(remaining - 1) }
                        .start()
                }
                .start()
        }
        doPulse(times)
    }

    private fun ensureConfiguredThen(action: () -> Unit) {
        if (configured && row.childCount >= visibleSlots) { action(); return }
        if (width > 0) { configureSlots(); action(); return }
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (width <= 0) return
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                configureSlots(); action()
            }
        })
    }
}
