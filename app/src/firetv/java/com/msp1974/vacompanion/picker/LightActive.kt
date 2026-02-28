package com.msp1974.vacompanion.picker

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Light binder:
 * - Attributes (max 2): brightness (%) and state (On/Off).
 * - Action on select: toggle on/off.
 * - Long-press/double-tap: enter brightness mode for step control.
 *
 * All state/logic for brightness control is contained here.
 */
class LightActive(private val pickerHost: PickerController.ControlHost) : PickerController.ControlModeBinder {

    // ---------------- Brightness mode state -----------------------------------
    private var brightnessModeActive = false
    private var brightnessModeEntityId: String? = null
    private var brightnessIdleTask: Runnable? = null
    private val BRIGHTNESS_IDLE_MS = 5000L

    /** Smooth UI step (%) regardless of device attribute representation. */
    private val BRIGHTNESS_STEP_PCT = 5

    /** Working smooth percent during brightness mode (0..100). */
    private val workingBrightnessPct: MutableMap<String, Int> = LinkedHashMap()

    // continuous pulse using one-shot pulses looped
    private var brightnessPulseTask: Runnable? = null
    private val BRIGHTNESS_PULSE_SEG_MS = 250L
    private val BRIGHTNESS_PULSE_SCALE = 1.20f
    private val BRIGHTNESS_PULSE_PERIOD = BRIGHTNESS_PULSE_SEG_MS * 2 + 80L
    // -------------------------------------------------------------------------

    /**
     * Helper to update local cache with new brightness (%) and re-render attribute lines.
     * Defined first to ensure visibility.
     */
    private fun patchBrightnessCacheAndRefresh(entityId: String, pct: Int) {
        // Build a mock state object to send back to PickerController to update its cache/UI
        val state = JSONObject()
        state.put("state", if (pct > 0) "on" else "off")

        val attrs = JSONObject()
        state.put("attributes", attrs)

        val bri255 = ((pct * 255.0) / 100.0).roundToInt().coerceIn(0, 255)
        attrs.put("brightness_pct", pct)
        attrs.put("brightness", bri255)

        // Request PickerController to update the state cache and refresh the UI for this entity
        pickerHost.updateEntityAttributes(entityId, state)
    }

    override fun buildAttributes(stateStr: String, attrs: JSONObject, preset: Preset): PickerController.AttributeResult {
        val out = ArrayList<String>(2)

        // Brightness shown as a percentage (if available).
        val pct = when {
            attrs.has("brightness_pct") -> attrs.optInt("brightness_pct", -1).coerceIn(0, 100)
            attrs.has("brightness") -> {
                val raw = attrs.optInt("brightness", -1)
                if (raw in 0..255) ((raw * 100.0) / 255.0).roundToInt().coerceIn(0, 100) else -1
            }
            stateStr.lowercase() == "on" -> {
                // If on but no brightness data, assume 100% for the initial state
                100
            }
            else -> -1
        }
        out += if (pct >= 0) "$pct%" else "--"

        // On/Off state.
        out += when (stateStr.lowercase()) {
            "on" -> "On"
            "off" -> "Off"
            else -> stateStr.ifBlank { "--" }
        }

        return PickerController.AttributeResult(out)
    }

    /** Toggle the light. This is the normal 'select' action. */
    override fun onSelect(
        preset: Preset,
        entityId: String?,
        attrs: JSONObject,
        haCall: PickerController.HaCaller
    ) {
        if (entityId.isNullOrBlank()) return
        val target = JSONObject()
        target.put("entity_id", entityId)

        // Use the HaCaller passed from PickerController for the simple toggle action
        haCall.invoke("light", "toggle", target, null)
    }

    /** Enter brightness mode for a given light entity. The picker stays visible. */
    override fun onLongPress(entityId: String, host: PickerController.ControlHost) {
        // We use the host's getLatestState method to fetch the initial brightness value
        val state = pickerHost.getLatestState(entityId)
        val attrs = state?.optJSONObject("attributes") ?: JSONObject()
        val seedPct = when {
            attrs.has("brightness_pct") -> attrs.optInt("brightness_pct", 0).coerceIn(0, 100)
            attrs.has("brightness") -> {
                val raw = attrs.optInt("brightness", -1)
                if (raw in 0..255) ((raw * 100.0) / 255.0).roundToInt().coerceIn(0, 100) else 0
            }
            state?.optString("state") == "on" -> 100
            else -> 0
        }
        workingBrightnessPct[entityId] = seedPct

        brightnessModeActive = true
        brightnessModeEntityId = entityId
        restartBrightnessIdle()
        startBrightnessPulse()
        pickerHost.log('i', "BrightnessMode", "entered for $entityId seed=$seedPct%")
    }

    override fun isActive(): Boolean = brightnessModeActive

    /** Handles 'prev', 'next', and 'select' keys when in brightness mode. */
    override fun onControlKey(key: String): Boolean {
        val entityId = brightnessModeEntityId ?: return false

        when (key) {
            "prev" -> {
                adjustLightBrightness(entityId, -BRIGHTNESS_STEP_PCT)
                restartBrightnessIdle()
                return true
            }
            "next" -> {
                adjustLightBrightness(entityId, +BRIGHTNESS_STEP_PCT)
                restartBrightnessIdle()
                return true
            }
            "select" -> {
                exitModeAndDismiss()
                return true
            }
            else -> return false
        }
    }

    /** Exit brightness mode and dismiss the picker. */
    override fun exitModeAndDismiss() {
        brightnessModeActive = false
        val id = brightnessModeEntityId
        brightnessModeEntityId = null
        cancelBrightnessIdle()
        stopBrightnessPulse()
        if (id != null) workingBrightnessPct.remove(id)
        pickerHost.log('i', "BrightnessMode", "exited for ${id ?: "-"} → dismiss picker")

        // Notify the controller that this special mode is no longer active
        pickerHost.onModeExited(this)

        pickerHost.dismissPicker()
    }

    /** Arm / re-arm brightness mode inactivity timer. */
    private fun restartBrightnessIdle() {
        cancelBrightnessIdle()
        brightnessIdleTask = Runnable { exitModeAndDismiss() }
        pickerHost.getHandler().postDelayed(brightnessIdleTask!!, BRIGHTNESS_IDLE_MS)
    }

    private fun cancelBrightnessIdle() {
        brightnessIdleTask?.let { pickerHost.getHandler().removeCallbacks(it) }
        brightnessIdleTask = null
    }

    private fun startBrightnessPulse() {
        stopBrightnessPulse()
        brightnessPulseTask = object : Runnable {
            override fun run() {
                pickerHost.getPicker().pulseSelectedIcon(times = 1, zoomScale = BRIGHTNESS_PULSE_SCALE, segmentMs = BRIGHTNESS_PULSE_SEG_MS)
                pickerHost.getHandler().postDelayed(this, BRIGHTNESS_PULSE_PERIOD)
            }
        }
        pickerHost.getHandler().post(brightnessPulseTask!!)
    }

    private fun stopBrightnessPulse() {
        brightnessPulseTask?.let { pickerHost.getHandler().removeCallbacks(it) }
        brightnessPulseTask = null
    }

    /**
     * Adjust brightness by a signed percentage delta (smooth, 0..100).
     */
    private fun adjustLightBrightness(entityId: String, deltaPct: Int) {
        val cur = workingBrightnessPct[entityId] ?: 0
        val targetPct = (cur + deltaPct).coerceIn(0, 100)
        workingBrightnessPct[entityId] = targetPct

        val target = JSONObject()
        target.put("entity_id", entityId)

        if (targetPct <= 0) {
            // Call HA service via the host
            pickerHost.callHaService("light", "turn_off", target, null)
            patchBrightnessCacheAndRefresh(entityId, 0)
        } else {
            val bri255 = ((targetPct * 255.0) / 100.0).roundToInt().coerceIn(1, 255)

            val data = JSONObject()
            data.put("brightness", bri255)

            // Call HA service via the host
            pickerHost.callHaService("light", "turn_on", target, data)
            patchBrightnessCacheAndRefresh(entityId, targetPct)
        }
    }
}