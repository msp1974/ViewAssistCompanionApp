package com.msp1974.vacompanion.players

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class SoundEffectsPlayer(val context: Context) {
    private val players = mutableMapOf<Int, ExoPlayer>()
    private val uriPlayers = mutableMapOf<Uri, ExoPlayer>()
    private var activeAdhocPlayer: ExoPlayer? = null
    private val _state = MutableStateFlow(Player.STATE_IDLE)
    val state: StateFlow<Int> = _state

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
            player.setMediaItem(mediaItem)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = playbackState
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
                            adhocPlayer.release()
                            if (activeAdhocPlayer == adhocPlayer) activeAdhocPlayer = null
                        }
                    })
                    adhocPlayer.prepare()
                    adhocPlayer.play()
                }
            } catch (ex: Exception) {
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
