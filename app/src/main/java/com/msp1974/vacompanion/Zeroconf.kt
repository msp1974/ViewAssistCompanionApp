package com.msp1974.vacompanion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.RegistrationListener
import android.net.nsd.NsdServiceInfo
import com.msp1974.vacompanion.settings.APPConfig
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal class Zeroconf(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private var registrationListener: RegistrationListener? = null
    private val config = APPConfig.getInstance(context)
    var serviceName: String? = null

    init {
        initializeRegistrationListener()
    }

    fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo()
        serviceInfo.serviceName = "vaca-${config.uuid}"
        serviceInfo.serviceType = "_vaca._tcp."
        serviceInfo.port = port

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        nsdManager!!.registerService(
            serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener
        )
    }

    fun unregisterService() {
        nsdManager!!.unregisterService(registrationListener)
    }

    fun initializeRegistrationListener() {
        registrationListener = object : RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                // Save the service name. Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                serviceName = NsdServiceInfo.serviceName
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Registration failed! Put debugging code here to determine why.
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                // Service has been unregistered. This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Unregistration failed. Put debugging code here to determine why.
            }
        }
    }
}
