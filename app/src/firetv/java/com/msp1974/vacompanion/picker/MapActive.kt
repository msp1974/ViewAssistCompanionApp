package com.msp1974.vacompanion.picker

import android.os.Handler
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * MapActive – Control mode for map zoom via shaft encoder.
 *
 * Expected JSON shape for map presets:
 *  - type: "call_service"
 *  - domain: "map"
 *  - target.entity_id: "input_number.map_focus_zoom_level"
 *  - path: "/view-assist/find-xxx-z"
 *
 * The path is read from PresetRepository and must be preserved for ACTIVE items.
 */
class MapActive(
    private val host: PickerController.ControlHost
) : PickerController.ControlModeBinder {

    private val tag = "MapActive"
    private val ui: Handler get() = host.getHandler()

    private val minZoom = 1
    private val maxZoom = 18
    private val step = 1
    private val inactivityMs: Long = 30_000L

    private var active = false
    private var zoomEntityId: String? = null
    private var currentZoom = 10
    private var idleTask: Runnable? = null

    override fun isActive(): Boolean = active

    override fun buildAttributes(
        stateStr: String,
        attrs: JSONObject,
        preset: Preset
    ): PickerController.AttributeResult {
        val line1 = preset.attributeLabels.getOrNull(0)?.let { label ->
            when (label.lowercase()) {
                "state" -> stateStr.ifBlank { "--" }
                "zoom" -> stateStr.ifBlank { "--" }
                else -> (attrs.opt(label)?.toString()?.ifBlank { "--" } ?: "--")
            }
        } ?: stateStr.ifBlank { "--" }

        val line2 = preset.attributeLabels.getOrNull(1)?.let { label ->
            when (label.lowercase()) {
                "zoom" -> stateStr.ifBlank { "--" }
                else -> (attrs.opt(label)?.toString()?.ifBlank { "--" } ?: "--")
            }
        } ?: ""

        val lines = listOf(line1, line2).filter { it.isNotBlank() }
        return PickerController.AttributeResult(lines)
    }

    override fun onSelect(
        preset: Preset,
        entityId: String?,
        attrs: JSONObject,
        haCall: PickerController.HaCaller
    ) {
        // Path must come from preset JSON (no hardcoded fallback).
        val path = preset.path?.trim()
        if (path.isNullOrBlank() || path == "do_not_select") {
            host.log('w', tag, "onSelect: preset missing valid path id=${preset.id}")
            // Still allow entering zoom mode if target exists.
            zoomEntityId = entityId ?: preset.target?.optString("entity_id")
            activateIfNeeded()
            return
        }

        zoomEntityId = entityId ?: preset.target?.optString("entity_id")

        host.preSizeForPath(path)
        host.navigateTo(path)

        activateIfNeeded()
    }

    override fun onLongPress(entityId: String, host: PickerController.ControlHost) {
        zoomEntityId = entityId
        activateIfNeeded()
    }

    override fun onControlKey(key: String): Boolean {
        if (!active) return false
        when (key) {
            "next" -> { setZoom(currentZoom + step, "step=+1"); return true }
            "prev" -> { setZoom(currentZoom - step, "step=-1"); return true }
            "select" -> { exitModeAndDismiss(); return true }
            "back" -> { exitModeAndDismiss(); return true }
        }
        return false
    }

    override fun exitModeAndDismiss() {
        if (!active) return
        active = false
        cancelIdle()
        host.log('i', tag, "exit zoom mode")
        host.onModeExited(this)
        host.dismissPicker()
    }

    // ---------------- Internals ----------------

    private fun activateIfNeeded() {
        if (active) {
            resetIdle("re-enter")
            return
        }
        active = true
        host.log('i', tag, "enter zoom mode entity=${zoomEntityId ?: "-"}")
        resetIdle("enter")

        // If we have a cached HA state for this entity, try to seed currentZoom.
        zoomEntityId?.let { id ->
            host.getLatestState(id)?.optString("state")?.toIntOrNull()?.let { z ->
                currentZoom = z.coerceIn(minZoom, maxZoom)
            }
        }
    }

    private fun setZoom(next: Int, why: String) {
        val clamped = min(max(next, minZoom), maxZoom)
        if (clamped == currentZoom) {
            resetIdle("no-change")
            return
        }
        currentZoom = clamped
        applyZoom(currentZoom)
        host.log('i', tag, "set zoom $why → $currentZoom")
        resetIdle("turn")
    }

    private fun applyZoom(value: Int) {
        val entity = zoomEntityId ?: return
        val target = JSONObject().put("entity_id", entity)
        val data = JSONObject().put("value", value)
        host.callHaService("input_number", "set_value", target, data)
    }

    private fun resetIdle(why: String) {
        cancelIdle()
        idleTask = Runnable {
            host.log('i', tag, "exit zoom mode inactivity ${inactivityMs}ms")
            exitModeAndDismiss()
        }.also { ui.postDelayed(it, inactivityMs) }
    }

    private fun cancelIdle() {
        idleTask?.let { ui.removeCallbacks(it) }
        idleTask = null
    }
}
