package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.msp1974.vacompanion.audio.AudioOutputRouter
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

// Matches MusicPlayerService's convention: SuppressLint (not @UnstableApi, which would make
// this class's own public API - play()/preload() etc., called from Satellite.kt - require every
// caller to opt in too) scoped to just this one unstable call (setPreferredAudioDevice).
@SuppressLint("UnsafeOptInUsageError")
class SoundEffectsPlayer(val context: Context, val config: APPConfig) : EventListener {
    private val players = mutableMapOf<Int, ExoPlayer>()
    private val uriPlayers = mutableMapOf<Uri, ExoPlayer>()
    private var activeAdhocPlayer: ExoPlayer? = null
    private val _state = MutableStateFlow(Player.STATE_IDLE)
    val state: StateFlow<Int> = _state

    init {
        config.eventBroadcaster.addListener(this)
    }

    override fun onEventTriggered(event: Event) {
        if (event.eventName == "audioOutputId") {
            // Config events arrive on any thread, but ExoPlayer may only be used from its own
            // (main) thread.
            Handler(Looper.getMainLooper()).post {
                val device = AudioOutputRouter.resolvePreferredDevice(context, config)
                players.values.forEach { it.setPreferredAudioDevice(device) }
                uriPlayers.values.forEach { it.setPreferredAudioDevice(device) }
                activeAdhocPlayer?.setPreferredAudioDevice(device)
            }
        }
    }

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_NOTIFICATION)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    suspend fun preload(uri: Uri) {
        if (uriPlayers.containsKey(uri)) return

        try {
            withContext(Dispatchers.Main) {
                val player = createPlayer(uri)
                player.prepare()
                uriPlayers[uri] = player
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    suspend fun unload(uri: Uri) {
        withContext(Dispatchers.Main) {
            uriPlayers[uri]?.release()
            uriPlayers.remove(uri)
        }
    }

    private fun createPlayer(resId: Int): ExoPlayer {
        return createPlayer(
            "android.resource://${context.packageName}/$resId".toUri()
        )
    }

    private fun createPlayer(uri: Uri): ExoPlayer {
        try {
            val player = ExoPlayer.Builder(context).build()
            val mediaItem = MediaItem.fromUri(uri)
            player.setAudioAttributes(audioAttributes, false)
            player.setPreferredAudioDevice(AudioOutputRouter.resolvePreferredDevice(context, config))
            player.setMediaItem(mediaItem)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = playbackState
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Cached players (uriPlayers/players) are reused across many play() calls -
                    // ExoPlayer stays stuck in the error state until prepare() is called again,
                    // so without this the sound effect would silently stop playing forever after
                    // one transient error. See the recovery check in play().
                    Timber.w(error, "Sound effect player error for $uri - will recover on next play()")
                }
            })
            return player
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    suspend fun play(resId: Int) {
        play("android.resource://${context.packageName}/$resId".toUri())
    }

    suspend fun play(uri: Uri) {
        withContext(Dispatchers.Main) {
            try {
                // Ensure only one feedback sound plays at a time
                stopAllInternal()

                val player = if (uri.scheme == "android.resource") {
                    val resId = uri.lastPathSegment?.toInt() ?: -1
                    players[resId]
                } else {
                    uriPlayers[uri]
                }

                if (player != null) {
                    if (player.playerError != null) {
                        // A previous playback error leaves ExoPlayer parked in STATE_IDLE with
                        // playerError set - play()/seekTo() are then no-ops until prepare() is
                        // called again to retry loading the same media item.
                        Timber.w("Recovering sound effect player for $uri after previous playback error")
                        player.prepare()
                    }
                    // These players are created ahead of time (preload), so the output device
                    // they were given may be stale: Android can renumber its audio devices when
                    // one such as a Bluetooth speaker connects, and a preferred device that no
                    // longer matches is ignored, leaving the sound on the new default (the
                    // speaker). Resolve it again now so the selected output is always honoured.
                    player.setPreferredAudioDevice(AudioOutputRouter.resolvePreferredDevice(context, config))
                    player.seekTo(0)
                    player.play()
                } else {
                    // Fallback for non-prepared sounds
                    val adhocPlayer = createPlayer(uri)
                    activeAdhocPlayer = adhocPlayer
                    adhocPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            _state.value = playbackState
                            if (playbackState == Player.STATE_ENDED) {
                                adhocPlayer.release()
                                if (activeAdhocPlayer == adhocPlayer) activeAdhocPlayer = null
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Timber.w(error, "Ad-hoc sound effect playback error for $uri")
                            adhocPlayer.release()
                            if (activeAdhocPlayer == adhocPlayer) activeAdhocPlayer = null
                        }
                    })
                    adhocPlayer.prepare()
                    adhocPlayer.play()
                }
            } catch (ex: Exception) {
                Timber.e("Error playing sound effect: ${ex.message}")
                ex.printStackTrace()
            }
        }
    }

    suspend fun stop() {
        stopAllInternal()
        release()
    }

    private suspend fun stopAllInternal() {
        withContext(Dispatchers.Main) {
            players.values.forEach {
                if (it.isPlaying) {
                    it.pause()
                    it.seekTo(0)
                }
            }
            uriPlayers.values.forEach {
                if (it.isPlaying) {
                    it.pause()
                    it.seekTo(0)
                }
            }
            activeAdhocPlayer?.let {
                it.stop()
                it.release()
                activeAdhocPlayer = null
            }
        }
    }

    suspend fun release() {
        config.eventBroadcaster.removeListener(this)
        withContext(Dispatchers.Main) {
            players.values.forEach { it.release() }
            players.clear()
            uriPlayers.values.forEach { it.release() }
            uriPlayers.clear()
            activeAdhocPlayer?.release()
            activeAdhocPlayer = null
        }
    }
}
