package com.msp1974.vacompanion.wyoming

import org.json.JSONObject

class WyomingPacket (event: JSONObject) {
    val type: String = event.getString("type")
    private var data: JSONObject = event.getJSONObject("data")
    var payload: ByteArray = ByteArray(0)

    fun getProp(prop: String): String {
        return data.getString(prop)
    }

    fun setProp(prop: String, value: String) {
        data.put(prop, value)
    }

    fun getDataLength(): Int {
        return data.toString().length
    }

    fun toMap(): MutableMap<String, Any> {
        return mutableMapOf("type" to type, "data" to data)
    }

}