package com.msp1974.vacompanion.utils

import java.net.Inet4Address
import java.net.NetworkInterface

class Helpers {
    companion object {
        fun getIpv4HostAddress(): String {
            NetworkInterface.getNetworkInterfaces()?.toList()?.map { networkInterface ->
                networkInterface.inetAddresses?.toList()?.find {
                    !it.isLoopbackAddress && it is Inet4Address
                }?.let { return it.hostAddress }
            }
            return ""
        }
    }
}