package com.msp1974.vacompanion.wyoming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Advertises the proxy as an ESPHome node (`_esphomelib._tcp`) so Home Assistant's ESPHome
 * integration discovers it. Registration retries after a failure, since it commonly fails when
 * the network isn't up yet (e.g. right after boot).
 */
class Zeroconf(
    context: Context,
    private val identity: WyomingIdentity,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())

    private var port = 0
    private var registered = false
    private var stopped = true

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
            Timber.d("Wyoming: advertised as ${info.serviceName} on port $port")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Timber.w("Wyoming: mDNS registration failed ($errorCode), retrying")
            if (!stopped) handler.postDelayed({ if (!stopped) register() }, RETRY_MS)
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Timber.w("Wyoming: mDNS unregistration failed ($errorCode)")
            registered = false
        }
    }

    fun start(port: Int) {
        this.port = port
        stopped = false
        register()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                Timber.w("Wyoming: error unregistering mDNS service: $e")
            }
        }
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = identity.nodeName
            serviceType = SERVICE_TYPE
            port = this@Zeroconf.port
            setAttribute("friendly_name", identity.friendlyName)
            setAttribute("version", identity.appVersion)
            // 12 lowercase hex chars, no separators - what ESPHome nodes publish.
            setAttribute("platform", "ANDROID")
            setAttribute("board", identity.model)
            setAttribute("network", "wifi")
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Timber.e("Wyoming: error registering mDNS service: $e")
            if (!stopped) handler.postDelayed({ if (!stopped) register() }, RETRY_MS)
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_vaca._tcp."
        private const val RETRY_MS = 15_000L
    }
}
