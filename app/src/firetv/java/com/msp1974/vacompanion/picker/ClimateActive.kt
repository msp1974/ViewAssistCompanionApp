package com.msp1974.vacompanion.picker

import android.graphics.Color
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Climate binder (standardized on the shared ActiveBinder interface).
 * - Attributes (2 lines):
 *   1) "current temperature: xx°C" (attributes.current_temperature)
 *   2) "target temperature: xx°C"  (attributes.temperature)
 * - Select: optional boost toggle (Boost 1h ↔ Cancel Overrides).
 * - Visual cue: line 2 turns red when "boost" is active.
 *
 * No control mode yet (future-proof via shared interface).
 */
class ClimateActive : PickerController.ActiveBinder {

    override fun buildAttributes(
        stateStr: String,
        attrs: JSONObject,
        preset: Preset
    ): PickerController.AttributeResult {
        val cur = attrs.optDouble("current_temperature", Double.NaN)
        val tgt = attrs.optDouble("temperature", Double.NaN)

        val curStr = if (cur.isNaN()) "--" else "${trim1(cur)}°C"
        val tgtStr = if (tgt.isNaN()) "--" else "${trim1(tgt)}°C"

        val line1 = "current temperature: $curStr"
        val line2 = "target temperature: $tgtStr"

        val boosted =
            attrs.optBoolean("is_boosted", false) ||
                    attrs.optInt("boost_time_remaining", 0) > 0 ||
                    attrs.optString("preset_mode", "").contains("Boost", ignoreCase = true)

        val color = if (boosted) Color.RED else null
        return PickerController.AttributeResult(listOf(line1, line2), line2Color = color)
    }

    override fun onSelect(
        preset: Preset,
        entityId: String?,
        attrs: JSONObject,
        haCall: PickerController.HaCaller
    ) {
        if (entityId.isNullOrBlank()) return
        val target = JSONObject().put("entity_id", entityId)

        val boosted =
            attrs.optBoolean("is_boosted", false) ||
                    attrs.optInt("boost_time_remaining", 0) > 0 ||
                    attrs.optString("preset_mode", "").contains("Boost", ignoreCase = true)

        if (boosted) {
            val data = JSONObject().put("preset_mode", "Cancel Overrides")
            haCall.invoke("climate", "set_preset_mode", target, data)
        } else {
            val data = JSONObject().put("preset_mode", "Boost 1h")
            haCall.invoke("climate", "set_preset_mode", target, data)
        }
    }

    private fun trim1(v: Double): String {
        val r = (v * 10.0).roundToInt() / 10.0
        val i = r.toInt()
        return if (r == i.toDouble()) i.toString() else r.toString()
    }
}
