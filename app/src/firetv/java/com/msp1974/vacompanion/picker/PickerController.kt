package com.msp1974.vacompanion.picker

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.webkit.WebView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.ble.BleRemoteService
import com.msp1974.vacompanion.overlay.OverlayHost
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.get
import kotlin.concurrent.thread
import kotlin.math.abs

class PickerController(
    private val activity: Activity,
    private val host: OverlayHost,
    private val navigateTo: (String) -> Unit,
    private val preSizeForPath: (String) -> Unit
) {

    private val log = Logger()

    // ---- Binder interfaces (controller must stay domain-agnostic) ----
    fun interface HaCaller {
        fun invoke(domain: String, service: String, target: JSONObject, data: JSONObject?)
    }

    data class AttributeResult(val lines: List<String>, val line2Color: Int? = null)

    interface ActiveBinder {
        fun buildAttributes(stateStr: String, attrs: JSONObject, preset: Preset): AttributeResult
        fun onSelect(preset: Preset, entityId: String?, attrs: JSONObject, haCall: HaCaller)
    }

    interface ControlModeBinder : ActiveBinder {
        fun onLongPress(entityId: String, host: ControlHost)
        fun onControlKey(key: String): Boolean
        fun isActive(): Boolean
        fun exitModeAndDismiss()
    }

    interface ControlHost {
        fun getHandler(): Handler
        fun getPicker(): PickerOverlayView
        fun getLatestState(entityId: String): JSONObject?
        fun callHaService(domain: String, service: String, target: JSONObject, data: JSONObject?)
        fun dismissPicker()
        fun updateEntityAttributes(entityId: String, stateObj: JSONObject)
        fun log(level: Char, tag: String, message: String)
        fun onModeExited(binder: ControlModeBinder)
        fun preSizeForPath(path: String)
        fun navigateTo(path: String)
    }

    private var currentControlBinder: ControlModeBinder? = null

    private inner class ControlHostImpl : ControlHost {
        override fun getHandler(): Handler = main
        override fun getPicker(): PickerOverlayView = picker
        override fun getLatestState(entityId: String): JSONObject? = latestStateByEntity[entityId]
        override fun callHaService(domain: String, service: String, target: JSONObject, data: JSONObject?) =
            haCallService(domain, service, target, data)
        override fun dismissPicker() = hidePicker()
        override fun updateEntityAttributes(entityId: String, stateObj: JSONObject) =
            updateAttributesForEntity(entityId, stateObj)
        override fun log(level: Char, tag: String, message: String) {
            val line = "$tag: $message"
            when (level) {
                'd' -> log.d(line)
                'i' -> log.i(line)
                'w' -> log.w(line)
                'e' -> log.e(line)
                else -> log.i(line)
            }
        }
        override fun onModeExited(binder: ControlModeBinder) {
            if (currentControlBinder == binder) currentControlBinder = null
        }
        override fun preSizeForPath(path: String) = this@PickerController.preSizeForPath(path)
        override fun navigateTo(path: String) = this@PickerController.navigateTo(path)
    }

    private val controlHostInstance = ControlHostImpl()

    // ---- Binder registry: no domain logic in controller ----
    private val lightBinder = LightActive(controlHostInstance)
    private val mapBinder = MapActive(controlHostInstance) //MH: MapActive gains 30s idle timer. //MH-end
    private val binders: Map<String, ActiveBinder> = mapOf(
        "light" to lightBinder,
        "map" to mapBinder,
        "climate" to ClimateActive()
    )

    // ---- Presets & picker UI ----
    private var repo = PresetRepository(activity)
    private var presets: List<Preset> = repo.load().ifEmpty {
        log.w("Picker: no presets from JSON; using built-in fallback")
        listOf(
            Preset(id = "camera", title = "Cameras", path = "/view-assist/camera", icon = "ic_dashboard_camera", type = PresetType.DASHBOARD),
            Preset(id = "weather", title = "Weather", path = "/view-assist/weather", icon = "ic_dashboard_weather", type = PresetType.DASHBOARD)
        )
    }
    init { log.i("Picker: presets loaded from source='${repo.lastSource()}' size=${presets.size}") }

    private val picker = PickerOverlayView(activity)
    private val main = Handler(Looper.getMainLooper())
    private val lbm by lazy { LocalBroadcastManager.getInstance(activity) }

    private val prefs = activity.getSharedPreferences("va_settings", Context.MODE_PRIVATE)
    private val idleMsDefault = 5000L
    private val idleKey = "picker_idle_ms"

    private var idleTimer: Runnable? = null
    private var visible = false

    @Volatile private var inputsEnabled = false
    private val ACTION_SYSTEM_READY = "com.msp1974.vacompanion.ACTION_SYSTEM_READY"

    private var pendingSteps: Int = 0
    private var applyTask: Runnable? = null
    private val APPLY_DELAY_MS = 60L
    private val MAX_BURST_STEPS = 200

    private var lastSelectAt = 0L
    private val SELECT_DEBOUNCE_MS = 200L

    private var statusIndex: Int = presets.indexOfFirst { it.id == "status" }
    private var lastBattPct: Int? = null
    private var lastRssi: Int? = null

    // ---- HA WebSocket (auth/states/subscribe/call_service) – unchanged ----
    private data class TokenState(var accessToken: String = "", var expiryEpochMs: Long = 0L)
    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val haWsAuthed = AtomicBoolean(false)
    private val haWsConnecting = AtomicBoolean(false)
    private val haMsgId = AtomicInteger(1)
    private var haWs: WebSocket? = null
    private val cached = TokenState()

    private val watchedEntities: MutableMap<String, MutableList<Int>> = LinkedHashMap()
    private var getStatesMsgId: Int = -1
    private var subscribeStatesMsgId: Int = -1

    private val latestStateByEntity: MutableMap<String, JSONObject> = LinkedHashMap()

    // ---- Wake helpers ----
    private var wakePending = false
    private var wakeAttempts = 0
    private val WAKE_RETRY_MS = 100L
    private val WAKE_MAX_ATTEMPTS = 30

    // ---- Remote JSON hot reload (ETag) ----
    private val FETCH_INTERVAL_MS = 60_000L
    private var fetchLoop: Runnable? = null
    private val FILE_NAME_LOCAL = "preset_dashboards.json"
    private val etagKeyUuid = "picker_etag_uuid"
    private val etagKeyDefault = "picker_etag_default"

    private fun deviceUuid(): String {
        val cfg = APPConfig.getInstance(activity)
        val cfgUuid = cfg.uuid?.trim().orEmpty()
        val androidId = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        return if (cfgUuid.isNotBlank()) cfgUuid else androidId
    }

    private fun currentHaOrigin(): String? {
        val v = host.webViewContainer as? WebView ?: return null
        val u = v.url ?: return null
        val uri = Uri.parse(u)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return null
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$portPart"
    }

    private fun beginFetchLoop() {
        fun scheduleNext(delay: Long) {
            fetchLoop = Runnable { fetchPresetFromHa(); scheduleNext(FETCH_INTERVAL_MS) }
            main.postDelayed(fetchLoop!!, delay)
        }
        scheduleNext(0L)
    }

    private fun stopFetchLoop() {
        fetchLoop?.let { main.removeCallbacks(it) }
        fetchLoop = null
    }

    private fun fetchPresetFromHa() {
        val origin = currentHaOrigin() ?: return
        startHaWebSocket()

        val uuid = deviceUuid()
        val urlUuid = "$origin/local/VACAoverlay/preset_dashboards_${uuid}.json"
        val urlDefault = "$origin/local/VACAoverlay/preset_dashboards_default.json"
        val etUuid = prefs.getString(etagKeyUuid, null)
        val etDef = prefs.getString(etagKeyDefault, null)

        thread(name = "Picker-json-fetch") {
            var changed = false

            val uuidRes = httpFetch(urlUuid, etUuid)
            when (uuidRes.code) {
                200 -> {
                    uuidRes.body?.let {
                        savePresetLocally(it)
                        prefs.edit().putString(etagKeyUuid, uuidRes.etag ?: "").apply()
                        prefs.edit().remove(etagKeyDefault).apply()
                        changed = true
                    }
                }
                304 -> {}
                404 -> {
                    val defRes = httpFetch(urlDefault, etDef)
                    if (defRes.code == 200) {
                        defRes.body?.let {
                            savePresetLocally(it)
                            prefs.edit().putString(etagKeyDefault, defRes.etag ?: "").apply()
                            prefs.edit().remove(etagKeyUuid).apply()
                            changed = true
                        }
                    }
                }
            }
            if (changed) main.post { hotReloadPresets() }
        }
    }

    private data class HttpResult(val code: Int, val etag: String?, val body: String?)
    private fun httpFetch(url: String, knownEtag: String?): HttpResult = try {
        val req = Request.Builder().url(url).apply {
            if (!knownEtag.isNullOrBlank()) header("If-None-Match", knownEtag)
        }.build()
        http.newCall(req).execute().use { r ->
            val etag = r.header("ETag")
            when (r.code) {
                200 -> HttpResult(200, etag, r.body?.string())
                304 -> HttpResult(304, knownEtag, null)
                404 -> HttpResult(404, null, null)
                else -> HttpResult(r.code, null, null)
            }
        }
    } catch (_: Exception) {
        HttpResult(-1, null, null)
    }

    private fun savePresetLocally(json: String) {
        try { File(activity.filesDir, FILE_NAME_LOCAL).writeText(json) } catch (_: Exception) {}
    }

    private fun hotReloadPresets() {
        repo = PresetRepository(activity)
        val newPresets = repo.load(); if (newPresets.isEmpty()) return
        presets = newPresets
        statusIndex = presets.indexOfFirst { it.id == "status" }

        watchedEntities.clear()
        presets.forEachIndexed { idx, p ->
            if (p.type == PresetType.ACTIVE) {
                val entityId = p.target?.optString("entity_id")?.trim().orEmpty()
                if (entityId.isNotEmpty()) watchedEntities.getOrPut(entityId) { mutableListOf() }.add(idx)
            }
        }

        picker.resetData()
        picker.setItems(presets)

        thread(name = "Picker-icon-loader") {
            presets.forEachIndexed { idx, p -> main.post { repo.loadIcon(p).let { picker.setIconDrawable(idx, it) } } }
        }

        presets.forEachIndexed { idx, p ->
            if (p.type == PresetType.ACTIVE) {
                val lines = when (p.attributeLabels.size) {
                    0 -> emptyList()
                    1 -> listOf(p.attributeLabels[0])
                    else -> p.attributeLabels.take(2)
                }
                picker.setAttributes(idx, lines)
            } else picker.setAttributes(idx, emptyList())
        }

        updateStatusTile()
    }

    // ---- Receivers (declared BEFORE init) ----
    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // battery/RSSI passthrough
            if (intent.hasExtra(BleRemoteService.EXTRA_BATTERY_PCT)) {
                val pct = intent.getIntExtra(BleRemoteService.EXTRA_BATTERY_PCT, -1)
                if (pct in 0..100) { lastBattPct = pct; updateStatusTile() }
            }
            val key = intent.getStringExtra(BleRemoteService.EXTRA_KEY) ?: return
            if (!inputsEnabled) return

            // ---- HARD LATCH: if a control mode is active, NEVER let picker logic run ----
            currentControlBinder?.let { binder ->
                if (binder.isActive()) {
                    val consumed = binder.onControlKey(key)
                    if (consumed) {
                        log.i("Picker: control-mode key='$key' consumed=true")
                        // DO NOT show/slide picker while in control mode.
                        // Also do not touch picker idle timer; binder owns its own timeout.
                        return
                    }
                }
            }

            when (key) {
                "long_press", "double_tap" -> {
                    if (!visible) return
                    val sel = picker.selectedItem() ?: return
                    if (sel.type != PresetType.ACTIVE) return
                    val domain = sel.domain?.lowercase()
                        ?: sel.target?.optString("entity_id")?.substringBefore('.', "")?.lowercase()
                    val binder = binders[domain]
                    if (binder is ControlModeBinder) {
                        val entityId = sel.target?.optString("entity_id").orEmpty()
                        if (entityId.isNotBlank()) {
                            binder.onLongPress(entityId, controlHostInstance)
                            currentControlBinder = binder //MH: take ownership so encoder never kicks us out. //MH-end
                            // no picker idle while in control mode
                            cancelIdle()
                        }
                    }
                }

                // When not in control mode, these navigate the picker carousel.
                // If a control mode becomes active later, enqueueStep() is also guard-railed.
                "prev" -> enqueueStep(-1)
                "next" -> enqueueStep(+1)

                "select" -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastSelectAt < SELECT_DEBOUNCE_MS) return
                    lastSelectAt = now

                    val sel = picker.selectedItem() ?: run { restartIdle(); return }
                    if (sel.type == PresetType.DASHBOARD && (sel.path == "do_not_select" || sel.id == "status")) {
                        restartIdle(); return
                    }
                    if (!visible && host.isOverlayExpanded()) {
                        navigateTo("/view-assist/clock")
                        return
                    }
                    if (ensureShownForFirstInput()) return

                    restartIdle()
                    applyTask?.let { main.removeCallbacks(it); it.run() }

                    when (sel.type) {
                        PresetType.DASHBOARD -> {
                            preSizeForPath(sel.path ?: "/view-assist/placeholder")
                            host.hidePicker(picker, restoreToWeb = true)
                            visible = false
                            navigateTo(sel.path ?: "/view-assist/placeholder")
                        }
                        PresetType.ACTIVE -> {
                            picker.pulseSelectedIcon(times = 2, zoomScale = 1.20f, segmentMs = 500L)
                            val domain = sel.domain?.lowercase()
                            val entityId = sel.target?.optString("entity_id")
                            val attrs = entityId?.let { latestStateByEntity[it]?.optJSONObject("attributes") } ?: JSONObject()
                            val binder = domain?.let { binders[it] }
                            if (binder != null) {
                                binder.onSelect(sel, entityId, attrs, ::haCallService)
                                if (binder is ControlModeBinder && binder.isActive()) {
                                    currentControlBinder = binder //MH: lock into control mode immediately on select. //MH-end
                                    // IMPORTANT: keep picker hidden while control mode is active
                                    cancelIdle()
                                }
                            } else if (!domain.isNullOrBlank() && !sel.service.isNullOrBlank() && sel.target != null) {
                                haCallService(domain, sel.service!!, sel.target, null)
                            }
                        }
                    }
                }

                "back" -> {
                    if (currentControlBinder?.isActive() == true) {
                        currentControlBinder?.exitModeAndDismiss()
                    } else {
                        hidePicker()
                    }
                }

                // Do not auto-show picker while a control mode could be active.
                "null" -> if (!visible && currentControlBinder?.isActive() != true) { showPicker(); restartIdle() }
            }
        }
    }

    private val connReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BleRemoteService.ACTION_CONNECTION_STATE) return
            val connected = intent.getBooleanExtra(BleRemoteService.EXTRA_CONNECTED, false)
            val rssi = if (intent.hasExtra(BleRemoteService.EXTRA_RSSI)) intent.getIntExtra(BleRemoteService.EXTRA_RSSI, 0) else null
            if (rssi != null) { lastRssi = rssi; updateStatusTile() }
            if (connected && !visible) requestWake()
        }
    }

    private val systemReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            inputsEnabled = true
            if (wakePending && !visible) requestWake()
            lbm.unregisterReceiver(this)
        }
    }

    private val satelliteRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            log.i("Picker: satelliteRx onReceive action=${intent.action}")
            when (intent.action) {
                "SATELLITE_STARTED" -> {
                    log.i("Picker: SATELLITE_STARTED → establishing HA WS")
                    startHaWebSocket()
                }
                "SATELLITE_STOPPED" -> {
                    log.i("Picker: SATELLITE_STOPPED → closing HA WS")
                    disposeHa()
                }
            }
        }
    }

    // ---- Initial mapping + receiver registration ----
    init {
        presets.forEachIndexed { idx, p ->
            if (p.type == PresetType.ACTIVE) {
                val entityId = p.target?.optString("entity_id")?.trim().orEmpty()
                if (entityId.isNotEmpty()) watchedEntities.getOrPut(entityId) { mutableListOf() }.add(idx)
            }
        }
        thread(name = "Picker-icon-loader") {
            presets.forEachIndexed { idx, p -> main.post { repo.loadIcon(p).let { picker.setIconDrawable(idx, it) } } }
        }
        picker.setItems(presets)
        presets.forEachIndexed { idx, p ->
            if (p.type == PresetType.ACTIVE) {
                val lines = when (p.attributeLabels.size) {
                    0 -> emptyList()
                    1 -> listOf(p.attributeLabels[0])
                    else -> p.attributeLabels.take(2)
                }
                picker.setAttributes(idx, lines)
            } else picker.setAttributes(idx, emptyList())
        }

        lbm.registerReceiver(bleReceiver, IntentFilter(BleRemoteService.ACTION_BUTTON))
        lbm.registerReceiver(connReceiver, IntentFilter(BleRemoteService.ACTION_CONNECTION_STATE))
        lbm.registerReceiver(systemReadyReceiver, IntentFilter(ACTION_SYSTEM_READY))

        val satelliteFilter = IntentFilter().apply {
            addAction("SATELLITE_STARTED")
            addAction("SATELLITE_STOPPED")
        }
        lbm.registerReceiver(satelliteRx, satelliteFilter)

        beginFetchLoop()
        updateStatusTile()
    }

    fun dispose() {
        try { lbm.unregisterReceiver(bleReceiver) } catch (_: Throwable) {}
        try { lbm.unregisterReceiver(connReceiver) } catch (_: Throwable) {}
        try { lbm.unregisterReceiver(systemReadyReceiver) } catch (_: Throwable) {}
        try { lbm.unregisterReceiver(satelliteRx) } catch (_: Throwable) {}
        stopFetchLoop()
        disposeHa()
    }

    // ---- HA websocket (unchanged logic) ----
    private fun disposeHa() {
        try { haWs?.close(1000, "picker-dispose") } catch (_: Exception) {}
        haWs = null; haWsAuthed.set(false); haWsConnecting.set(false)
        getStatesMsgId = -1; subscribeStatesMsgId = -1
    }

    private fun haCallService(
        domain: String,
        service: String,
        target: JSONObject,
        data: JSONObject? = null
    ) {
        if (!haWsAuthed.get()) {
            log.w("HA WS: call_service ignored – HA WS not connected")
            return
        }
        val id = haMsgId.getAndIncrement()
        val payload = JSONObject()
            .put("id", id)
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
            .put("target", target)
        if (data != null) payload.put("service_data", data)
        haWs?.send(payload.toString())
        log.i("HA WS: call_service $domain.$service → target=$target data=${data ?: "{}"}")
    }

    private fun startHaWebSocket() {
        val alreadyAuthed = haWsAuthed.get()
        val alreadyConnecting = haWsConnecting.get()
        if (alreadyAuthed || alreadyConnecting) return

        val origin = currentHaOrigin() ?: return
        val cfg = APPConfig.getInstance(activity)
        val cfgAccess = cfg.accessToken?.trim().orEmpty()
        val cfgRefresh = cfg.refreshToken?.trim().orEmpty()

        val access = if (cfgAccess.isNotEmpty()) cfgAccess else exchangeRefreshForAccess(origin, cfgRefresh).orEmpty()
        if (access.isEmpty()) return

        openHaWebSocket(origin, access, onAuthed = {}, fallbackRefresh = cfgRefresh.takeIf { it.isNotEmpty() })
    }

    private fun openHaWebSocket(
        origin: String,
        accessToken: String,
        onAuthed: () -> Unit,
        fallbackRefresh: String?
    ) {
        if (haWsConnecting.get()) return
        haWsConnecting.set(true)

        val wsUrl = if (origin.startsWith("https://", true))
            origin.replaceFirst("https://", "wss://") + "/api/websocket"
        else
            origin.replaceFirst("http://", "ws://") + "/api/websocket"

        val req = Request.Builder().url(wsUrl).build()
        haWs = http.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                log.i("HA WS: onOpen HTTP=${response.code} url=$wsUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "auth_required" -> {
                            webSocket.send(
                                JSONObject().put("type", "auth").put("access_token", accessToken).toString()
                            )
                        }
                        "auth_ok" -> {
                            haWsAuthed.set(true)
                            haWsConnecting.set(false)
                            main.post {
                                requestAllStates()
                                subscribeStateChanged()
                                onAuthed()
                            }
                        }
                        "auth_invalid" -> {
                            haWsAuthed.set(false)
                            haWsConnecting.set(false)
                            if (!fallbackRefresh.isNullOrBlank()) {
                                val fresh = exchangeRefreshForAccess(origin, fallbackRefresh)
                                if (!fresh.isNullOrEmpty()) {
                                    disposeHa()
                                    openHaWebSocket(origin, fresh, onAuthed, null)
                                }
                            }
                        }
                        "result" -> handleResult(msg)
                        "event"  -> handleEvent(msg)
                    }
                } catch (e: Exception) {
                    log.e("HA WS: parse exception: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                haWsAuthed.set(false)
                haWsConnecting.set(false)
                log.e("HA WS: onFailure ${t::class.java.simpleName}: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                haWsAuthed.set(false)
                haWsConnecting.set(false)
                log.w("HA WS: onClosed code=$code reason='$reason'")
            }
        })
    }

    private fun requestAllStates() {
        if (watchedEntities.isEmpty()) return
        getStatesMsgId = haMsgId.getAndIncrement()
        haWs?.send(JSONObject().put("id", getStatesMsgId).put("type", "get_states").toString())
    }

    private fun subscribeStateChanged() {
        if (watchedEntities.isEmpty()) return
        subscribeStatesMsgId = haMsgId.getAndIncrement()
        haWs?.send(JSONObject().put("id", subscribeStatesMsgId).put("type", "subscribe_events").put("event_type", "state_changed").toString())
    }

    private fun handleResult(msg: JSONObject) {
        if (msg.optInt("id", -1) == getStatesMsgId) {
            val result = msg.optJSONArray("result") ?: JSONArray()
            for (i in 0 until result.length()) {
                val s = result.optJSONObject(i) ?: continue
                val entityId = s.optString("entity_id", "")
                if (entityId.isBlank() || !watchedEntities.containsKey(entityId)) continue
                val normalized = normalizeStateObject(s)
                updateAttributesForEntity(entityId, normalized)
            }
        }
    }

    private fun handleEvent(msg: JSONObject) {
        val event = msg.optJSONObject("event") ?: return
        val data = event.optJSONObject("data") ?: return
        val entityId = data.optString("entity_id", "")
        if (entityId.isBlank() || !watchedEntities.containsKey(entityId)) return
        val newState = data.optJSONObject("new_state") ?: return
        updateAttributesForEntity(entityId, newState)
    }

    private fun normalizeStateObject(obj: JSONObject): JSONObject = when {
        obj.has("new_state") -> obj.optJSONObject("new_state") ?: obj
        obj.has("state") && obj.opt("state") is JSONObject -> obj.optJSONObject("state")!!
        else -> JSONObject().apply {
            put("state", obj.optString("state", ""))
            put("attributes", obj.optJSONObject("attributes") ?: JSONObject())
        }
    }

    private fun updateAttributesForEntity(entityId: String, stateObj: JSONObject) {
        latestStateByEntity[entityId] = stateObj

        val targets = watchedEntities[entityId] ?: return
        val stateStr = stateObj.optString("state", "")
        val attrs = stateObj.optJSONObject("attributes") ?: JSONObject()

        targets.forEach { presetIndex ->
            val p = presets.getOrNull(presetIndex) ?: return@forEach
            val domain = p.domain?.lowercase()
                ?: p.target?.optString("entity_id")?.substringBefore('.', "")?.lowercase()

            val binder = binders[domain]
            val result = binder?.buildAttributes(stateStr, attrs, p) ?: buildAttributeLinesDefault(p, stateStr, attrs)

            main.post {
                picker.setAttributes(presetIndex, result.lines)
                val color = result.line2Color ?: PickerOverlayView.DEFAULT_ATTR_COLOR
                picker.setAttributeColor(presetIndex, 2, color)
                picker.setAttributeColor(presetIndex, 1, PickerOverlayView.DEFAULT_ATTR_COLOR)
            }
        }
    }

    private fun buildAttributeLinesDefault(p: Preset, stateStr: String, attrs: JSONObject): AttributeResult {
        if (p.attributeLabels.isEmpty()) return AttributeResult(emptyList())
        val out = ArrayList<String>(2)
        p.attributeLabels.take(2).forEach { label ->
            val v = when (label.lowercase()) {
                "state" -> when (stateStr.lowercase()) { "on" -> "On"; "off" -> "Off"; else -> stateStr.ifBlank { "--" } }
                else -> (attrs.opt(label)?.toString()?.ifBlank { "--" } ?: "--")
            }
            out += v
        }
        return AttributeResult(out)
    }

    private fun exchangeRefreshForAccess(origin: String, refreshToken: String?): String? {
        if (refreshToken.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        if (cached.accessToken.isNotBlank() && now < cached.expiryEpochMs - 15_000) return cached.accessToken
        val url = "$origin/auth/token"
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val req = Request.Builder().url(url).post(form).header("Content-Type", "application/x-www-form-urlencoded").build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val access = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 0L)
                if (access.isBlank() || expiresIn <= 0) return null
                cached.accessToken = access
                cached.expiryEpochMs = now + expiresIn * 1000
                access
            }
        } catch (_: Exception) { null }
    }

    // ---- Picker show/hide + idle ----
    private fun requestWake() {
        if (visible) return
        if (!host.isOverlayExpanded()) return
        wakePending = true
        wakeAttempts = 0
        tryWakeLoop()
    }

    private fun tryWakeLoop() {
        if (!wakePending) return
        if (!host.isOverlayExpanded()) {
            if (++wakeAttempts < WAKE_MAX_ATTEMPTS) {
                main.postDelayed({ tryWakeLoop() }, WAKE_RETRY_MS)
            } else {
                wakePending = false
            }
            return
        }
        wakePending = false
        showPicker()
        restartIdle()
    }

    private fun ensureShownForFirstInput(): Boolean {
        if (!visible) {
            applyTask?.let { main.removeCallbacks(it); applyTask = null }
            pendingSteps = 0
            showPicker()
            restartIdle()
            return true
        }
        return false
    }

    private fun slideSteps(steps: Int) {
        if (steps != 0) picker.slideBy(if (steps > 0) +1 else -1, abs(steps))
    }

    private fun enqueueStep(delta: Int) {
        // EXTRA GUARD: if a control mode is active, never move/show the picker.
        if (currentControlBinder?.isActive() == true) return

        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { enqueueStep(delta) }; return }
        if (!inputsEnabled) return
        if (!visible && host.isOverlayExpanded()) { showPicker(); restartIdle(); return }
        if (ensureShownForFirstInput()) return
        restartIdle()
        pendingSteps = (pendingSteps + delta).coerceIn(-MAX_BURST_STEPS, MAX_BURST_STEPS)
        if (applyTask == null) {
            applyTask = Runnable {
                val s = pendingSteps
                pendingSteps = 0
                applyTask = null
                slideSteps(s)
            }
            main.postDelayed(applyTask!!, APPLY_DELAY_MS)
        }
    }

    private fun showPicker() {
        if (!visible) {
            host.showPicker(picker)
            picker.show()
            visible = true
        }
    }

    private fun hidePicker() {
        if (!visible) return
        cancelIdle()
        applyTask?.let { main.removeCallbacks(it); it.run() }
        picker.hide {
            host.hidePicker(picker, restoreToWeb = false, forceBubble = true)
            visible = false
        }
    }

    private fun restartIdle() {
        if (currentControlBinder?.isActive() == true) return
        cancelIdle()
        val ms = prefs.getLong(idleKey, idleMsDefault)
        idleTimer = Runnable { hidePicker() }
        main.postDelayed(idleTimer!!, ms)
    }

    private fun cancelIdle() { idleTimer?.let { main.removeCallbacks(it) }; idleTimer = null }

    private fun updateStatusTile() {
        if (statusIndex < 0 || statusIndex >= presets.size) return
        // status tile text handled elsewhere (unchanged)
    }
}
