package com.msp1974.vacompanion.sendspin

import android.content.Context
import android.content.pm.PackageManager
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import timber.log.Timber

/**
 * Owns the Sendspin player's lifetime for as long as the background task runs, independent of
 * whether a Wyoming/HA satellite session is connected. Starts and stops it as the
 * [com.msp1974.vacompanion.settings.APPConfig.sendspinEnabled] setting changes, following the same
 * config-event-driven pattern as [com.msp1974.vacompanion.service.rtsp.RtspServerManager].
 */
class SendspinManager(
    private val context: Context,
    deviceManager: DeviceManager,
) : EventListener {

    private val config = deviceManager.config
    private var player: SendspinPlayer? = null
    private var advertiser: SendspinAdvertiser? = null

    fun start() {
        config.eventBroadcaster.addListener(this)
        if (config.sendspinEnabled) startPlayer()
    }

    fun shutdown() {
        config.eventBroadcaster.removeListener(this)
        stopPlayer()
    }

    @Synchronized
    private fun startPlayer() {
        if (player != null) return
        val deviceName = Helpers.getDeviceName() ?: config.uuid
        val newPlayer = SendspinPlayer(
            context = context,
            config = config,
            clientId = "vaca-${config.uuid}",
            playerName = "${config.name} $deviceName",
            softwareVersion = appVersion(),
        )
        if (!newPlayer.isRunning) {
            newPlayer.destroy()
            return
        }
        player = newPlayer
        advertiser = SendspinAdvertiser(
            context = context,
            serviceName = "vaca-sendspin-${config.uuid}",
            friendlyName = "${config.name} $deviceName",
        ).also { it.start(newPlayer.port) }
        config.sendspinRunning = true
    }

    @Synchronized
    private fun stopPlayer() {
        advertiser?.stop()
        advertiser = null
        player?.destroy()
        player = null
        config.sendspinRunning = false
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    // The setting is SharedPreferences-backed, so the event carries no typed value - read it back.
    override fun onEventTriggered(event: Event) {
        if (event.eventName != "sendspin_enabled") return
        Timber.d("SendspinManager: sendspin_enabled=${config.sendspinEnabled}")
        if (config.sendspinEnabled) startPlayer() else stopPlayer()
    }
}
