package com.msp1974.vacompanion.picker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.utils.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class PresetType { DASHBOARD, ACTIVE }

data class Preset(
    val id: String,
    val title: String,
    val path: String? = null,               // dashboard path OR optional path for ACTIVE presets
    val icon: String = "ic_dashboard_placeholder",
    val type: PresetType = PresetType.DASHBOARD,
    val domain: String? = null,             // active-only
    val service: String? = null,            // active-only
    val target: JSONObject? = null,         // active-only
    val attributeLabels: List<String> = emptyList()
)

class PresetRepository(private val context: Context) {
    private val log = Logger()

    // Remember where the JSON actually came from
    private var lastSourceInternal: String = "unknown" // filesDir|assets|raw|null
    fun lastSource(): String = lastSourceInternal

    fun load(): List<Preset> {
        val json = readPresetJson() ?: return emptyList()
        val arr = JSONArray(json)
        val out = ArrayList<Preset>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val title = o.optString("title", id)
            val icon = o.optString("icon", "ic_dashboard_placeholder")

            // IMPORTANT:
            // Path can exist for both DASHBOARD and ACTIVE items (e.g. map zoomable presets).
            val path = o.optString("path", "").trim().takeIf { it.isNotBlank() }

            when (o.optString("type", "").lowercase()) {
                "call_service" -> {
                    val domain = o.optString("domain").ifBlank { null }
                    val service = o.optString("service").ifBlank { null }
                    val target = o.optJSONObject("target")
                    val labels = parseAttributeLabels(o.opt("attributes"))

                    out += Preset(
                        id = id,
                        title = title,
                        path = path, // <-- FIX: preserve path for ACTIVE presets
                        icon = icon,
                        type = PresetType.ACTIVE,
                        domain = domain,
                        service = service,
                        target = target,
                        attributeLabels = labels
                    )
                }
                else -> {
                    val dashPath = path ?: "do_not_select"
                    out += Preset(
                        id = id,
                        title = title,
                        path = dashPath,
                        icon = icon,
                        type = PresetType.DASHBOARD
                    )
                }
            }
        }

        log.i("PresetRepository: loaded ${out.size} items (source='${lastSourceInternal}')")
        return out
    }

    private fun parseAttributeLabels(any: Any?): List<String> {
        val labels = ArrayList<String>(2)
        when (any) {
            is JSONArray -> {
                for (i in 0 until any.length()) {
                    if (labels.size >= 2) break
                    any.optString(i)?.takeIf { it.isNotBlank() }?.let { labels += it }
                }
            }
            is JSONObject -> {
                val it = any.keys()
                while (it.hasNext() && labels.size < 2) labels += it.next()
                if (labels.size < 2) {
                    any.keys().forEachRemaining { k ->
                        if (labels.size < 2) {
                            val v = any.optString(k, "")
                            if (v.isNotBlank() && !labels.contains(v)) labels += v
                        }
                    }
                }
            }
        }
        return labels.take(2)
    }

    fun loadIcon(p: Preset): Drawable {
        val spec = p.icon
        return when {
            spec.startsWith("http://") || spec.startsWith("https://") ->
                fetchBitmap(spec)?.toDrawable() ?: placeholder()

            spec.startsWith("file://") -> {
                val f = File(spec.removePrefix("file://"))
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.toDrawable() ?: placeholder()
                else placeholder()
            }

            else -> {
                val resId = context.resources.getIdentifier(spec, "drawable", context.packageName)
                if (resId != 0) context.getDrawable(resId) ?: placeholder()
                else placeholder()
            }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? = try {
        val u = URL(url)
        val c = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun Bitmap.toDrawable(): Drawable = BitmapDrawable(context.resources, this)
    private fun placeholder(): Drawable =
        context.getDrawable(R.drawable.ic_dashboard_placeholder)!!

    // Priority: filesDir (updated by controller) → assets → raw (fallback)
    private fun readPresetJson(): String? {
        val file = File(context.filesDir, "preset_dashboards.json")
        try {
            if (file.exists()) {
                val text = file.readText()
                lastSourceInternal = "filesDir:${file.absolutePath}"
                log.i("PresetRepository: using filesDir JSON path='${file.absolutePath}' bytes=${text.length}")
                return text
            } else {
                log.i("PresetRepository: filesDir JSON not found at ${file.absolutePath}")
            }
        } catch (e: Exception) {
            log.w("PresetRepository: failed reading filesDir JSON at ${file.absolutePath} — ${e.message}")
        }

        try {
            context.assets.open("preset_dashboards.json").bufferedReader().use { br ->
                val text = br.readText()
                lastSourceInternal = "assets"
                log.i("PresetRepository: using assets/preset_dashboards.json bytes=${text.length}")
                return text
            }
        } catch (_: Exception) {
            log.i("PresetRepository: assets JSON not present")
        }

        return try {
            val rawId = context.resources.getIdentifier("preset_dashboards", "raw", context.packageName)
            if (rawId != 0) {
                val text = context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
                lastSourceInternal = "raw:res/raw/preset_dashboards.json"
                log.i("PresetRepository: using raw resource bytes=${text.length}")
                text
            } else {
                lastSourceInternal = "null"
                log.w("PresetRepository: no JSON found (filesDir/assets/raw). Using empty list.")
                null
            }
        } catch (e: Exception) {
            lastSourceInternal = "null"
            log.w("PresetRepository: error reading raw JSON — ${e.message}")
            null
        }
    }
}
