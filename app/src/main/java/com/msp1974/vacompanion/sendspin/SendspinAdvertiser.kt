package com.msp1974.vacompanion.sendspin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Advertises the Sendspin player over mDNS (`_sendspin._tcp`) so servers can find it and open the
 * WebSocket connection to [SendspinPlayer.port]. Registration retries after a failure, since it
 * commonly fails when the network isn't up yet (e.g. right after boot).
 */
class SendspinAdvertiser(
    private val context: Context,
    private val serviceName: String,
    private val friendlyName: String,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())

    private var port = 0
    private var registered = false
    private var stopped = true

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
            Timber.d("Sendspin: advertised as ${info.serviceName} on port $port")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Timber.w("Sendspin: mDNS registration failed ($errorCode), retrying")
            if (!stopped) handler.postDelayed({ if (!stopped) register() }, RETRY_MS)
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Timber.w("Sendspin: mDNS unregistration failed ($errorCode)")
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
                Timber.w("Sendspin: error unregistering mDNS service: $e")
            }
        }
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = this@SendspinAdvertiser.serviceName
            serviceType = SERVICE_TYPE
            port = this@SendspinAdvertiser.port
            // The path servers open the WebSocket on, and the name shown in their player list.
            setAttribute("path", PATH)
            setAttribute("name", friendlyName)
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Timber.e("Sendspin: error registering mDNS service: $e")
            if (!stopped) handler.postDelayed({ if (!stopped) register() }, RETRY_MS)
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_sendspin._tcp."
        private const val PATH = "/sendspin"
        private const val RETRY_MS = 15_000L
    }
}
