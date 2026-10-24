package com.msp1974.vacompanion.sendspin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Patterns
import com.msp1974.sendspin.NativeSendspin
import com.msp1974.sendspin.NativeSendspinCallbacks
import com.msp1974.vacompanion.broadcasts.VacaBroadcastMessage
import com.msp1974.vacompanion.audio.AudioOutputRouter
import com.msp1974.vacompanion.players.VoicePlayerService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import timber.log.Timber
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One Sendspin player: the native sendspin-cpp engine plus the Android audio output and the
 * playback feedback loop that keeps it synchronised with the server.
 *
 * Home Assistant / Music Assistant connect to this device (it is advertised over mDNS by
 * [SendspinAdvertiser]) and stream audio to it. Audio focus is handled the same way as the other
 * players, so music ducks while the voice assistant is listening or speaking.
 *
 * Bad construction (e.g. no free port) is reported through [isRunning] rather than an exception.
 */
class SendspinPlayer(
    private val context: Context,
    private val config: APPConfig,
    clientId: String,
    playerName: String,
    softwareVersion: String,
) : NativeSendspinCallbacks, EventListener {

    private val output = SendspinAudioOutput()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val destroyed = AtomicBoolean(false)

    @Volatile
    private var handle = 0L

    @Volatile
    private var feedbackThread: Thread? = null

    /** The port the Sendspin server connects to, valid while [isRunning]. */
    var port = 0
        private set

    val isRunning: Boolean get() = handle != 0L

    val isConnected: Boolean get() = handle != 0L && NativeSendspin.nativeIsConnected(handle)

    // Player volume as the server sees it (0-100), and the ducking applied on top of it.
    @Volatile private var volume = prefs.getInt(PREF_VOLUME, config.musicVolume).coerceIn(0, 100)
    @Volatile private var muted = prefs.getBoolean(PREF_MUTED, false)
    @Volatile private var ducked = false
    @Volatile private var duckRampGain = 1f
    private var hasAudioFocus = false
    private var focusRequest: AudioFocusRequest? = null

    // What the now-playing overlay shows; see publishNowPlaying().
    private val nowPlayingLock = Any()
    private var streamActive = false
    private var groupPlaying = false
    private var title = ""
    private var artist = ""
    private var album = ""
    private var artworkUrl: String? = null
    private var durationMs = 0L
    private var positionMs = 0L
    private var positionAtMs = 0L
    private var speed = -1
    private var commands = emptySet<String>()
    private var shuffle = false
    private var repeat = "off"
    private var colors = SendspinColors()
    private var published = SendspinNowPlaying()

    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    init {
        output.setPreferredDevice(AudioOutputRouter.resolvePreferredDevice(context, config))
        applyGain()

        val formats = intArrayOf(
            // codec, channels, sample rate, bit depth - in the order offered to the server.
            NativeSendspin.CODEC_FLAC, 2, SAMPLE_RATE, 16,
            NativeSendspin.CODEC_PCM, 2, SAMPLE_RATE, 16,
            NativeSendspin.CODEC_OPUS, 2, SAMPLE_RATE, 16,
            NativeSendspin.CODEC_FLAC, 1, SAMPLE_RATE, 16,
            NativeSendspin.CODEC_PCM, 1, SAMPLE_RATE, 16,
            NativeSendspin.CODEC_OPUS, 1, SAMPLE_RATE, 16,
        )

        // The default port may be taken (e.g. another Sendspin client on the device). The library
        // doesn't report a failed bind from start(), so find a free port first.
        val candidate = (BASE_PORT until BASE_PORT + PORT_ATTEMPTS).firstOrNull { isPortFree(it) }
        if (candidate == null) {
            Timber.e("Sendspin: no free port in $BASE_PORT..${BASE_PORT + PORT_ATTEMPTS - 1}")
        } else {
            val created = NativeSendspin.nativeCreate(
                callbacks = this,
                clientId = clientId,
                name = playerName,
                productName = "View Assist Companion App",
                manufacturer = "VACA",
                softwareVersion = softwareVersion,
                serverPort = candidate,
                audioBufferCapacity = AUDIO_BUFFER_BYTES,
                formats = formats,
            )
            if (created != 0L) {
                if (NativeSendspin.nativeStart(created)) {
                    handle = created
                    port = candidate
                } else {
                    NativeSendspin.nativeDestroy(created)
                }
            }
        }

        if (handle == 0L) {
            Timber.e("Sendspin: failed to start")
        } else {
            NativeSendspin.nativeUpdateVolume(handle, volume)
            NativeSendspin.nativeUpdateMuted(handle, muted)
            NativeSendspin.nativeSetPreferredServer(handle, preferredServerIp())
            config.eventBroadcaster.addListener(this)
            startFeedbackLoop()
            Timber.i("Sendspin: listening on port $port")
        }
    }

    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: IOException) {
        false
    }

    // The IPv4 address in the preferred-server URL, or blank (Auto) when there is none.
    private fun preferredServerIp(): String {
        val url = config.sendspinPreferredServer
        if (url.isBlank()) return ""
        val host = try {
            URI(if ("://" in url) url else "http://$url").host
        } catch (_: URISyntaxException) {
            null
        }
        if (host == null || !Patterns.IP_ADDRESS.matcher(host).matches()) {
            Timber.w("Sendspin: preferred server '$url' has no IPv4 address, accepting any server")
            return ""
        }
        return host
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        config.eventBroadcaster.removeListener(this)
        feedbackThread?.interrupt()
        feedbackThread = null
        mainHandler.removeCallbacksAndMessages(null)
        val h = handle
        handle = 0L
        if (h != 0L) NativeSendspin.nativeDestroy(h)
        output.release()
        abandonAudioFocus()
        resetNowPlaying()
        Timber.i("Sendspin: stopped")
    }

    // ---- Playback feedback ---------------------------------------------------------------

    // The library schedules audio against how much has actually been presented, so report that
    // frequently from a thread that isn't starved by UI work.
    private fun startFeedbackLoop() {
        val thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (!destroyed.get()) {
                val h = handle
                if (h != 0L) {
                    output.pollPlayed()?.let { played ->
                        NativeSendspin.nativeNotifyAudioPlayed(h, played.frames.toInt(), played.atUs)
                    }
                }
                try {
                    Thread.sleep(FEEDBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        thread.name = "SendspinFeedback"
        thread.isDaemon = true
        feedbackThread = thread
        thread.start()
    }

    // ---- NativeSendspinCallbacks ---------------------------------------------------------

    override fun onAudioWrite(buffer: ByteBuffer, length: Int, timeoutMs: Int): Int =
        output.write(buffer, length, timeoutMs)

    override fun onStreamStart(sampleRate: Int, channels: Int, bitDepth: Int) {
        Timber.d("Sendspin: stream start rate=$sampleRate channels=$channels bitDepth=$bitDepth")
        synchronized(nowPlayingLock) { streamActive = true }
        publishNowPlaying()
        // Only one source at a time: the music player yields to Sendspin.
        config.eventBroadcaster.notifyEvent(Event("sendspinPlaybackStarted", "", ""))
        if (!output.start(sampleRate, channels, bitDepth)) return
        // The voice assistant may already hold focus; a fresh request then wins outright rather
        // than ducking us, so start ducked ourselves and let onVoicePlaybackEnded() release it.
        ducked = voiceHoldsFocus()
        duckRampGain = if (ducked) duckLevel() else 1f
        applyGain()
        requestAudioFocus()
    }

    override fun onStreamEnd() {
        Timber.d("Sendspin: stream end")
        output.pause()
        abandonAudioFocus()
        synchronized(nowPlayingLock) { streamActive = false }
        publishNowPlaying()
    }

    override fun onVolumeChanged(volume: Int) {
        this.volume = volume
        prefs.edit().putInt(PREF_VOLUME, volume).apply()
        applyGain()
    }

    /** Sets the volume from this device rather than the server, and tells the server. */
    private fun setVolumeFromDevice(percent: Int) {
        val value = percent.coerceIn(0, 100)
        onVolumeChanged(value)
        val h = handle
        if (h != 0L) NativeSendspin.nativeUpdateVolume(h, value)
    }

    override fun onMuteChanged(muted: Boolean) {
        this.muted = muted
        prefs.edit().putBoolean(PREF_MUTED, muted).apply()
        applyGain()
    }

    override fun onConnectionChanged(connected: Boolean, serverName: String?) {
        Timber.i("Sendspin: ${if (connected) "connected to $serverName" else "disconnected"}")
        if (!connected) {
            output.pause()
            abandonAudioFocus()
            resetNowPlaying()
            config.sendspinGroupId = ""
        }
    }

    override fun onMetadata(
        title: String?,
        artist: String?,
        album: String?,
        artworkUrl: String?,
        progressMs: Long,
        durationMs: Long,
        speed: Int,
    ) {
        synchronized(nowPlayingLock) {
            this.title = title.orEmpty()
            this.artist = artist.orEmpty()
            this.album = album.orEmpty()
            this.artworkUrl = artworkUrl
            this.durationMs = durationMs.coerceAtLeast(0)
            this.positionMs = progressMs.coerceAtLeast(0)
            this.positionAtMs = SystemClock.elapsedRealtime()
            this.speed = speed
        }
        publishNowPlaying()
    }

    override fun onMetadataCleared() {
        resetNowPlaying()
    }

    override fun onGroupChanged(groupId: String) {
        config.sendspinGroupId = groupId
    }

    override fun onPlaybackStateChanged(playing: Boolean) {
        synchronized(nowPlayingLock) { groupPlaying = playing }
        publishNowPlaying()
    }

    override fun onControllerState(commands: String, shuffle: Boolean, repeat: String) {
        synchronized(nowPlayingLock) {
            this.commands = commands.split(',').filter { it.isNotEmpty() }.toSet()
            this.shuffle = shuffle
            this.repeat = repeat
        }
        publishNowPlaying()
    }

    override fun onColors(backgroundDark: Int, backgroundLight: Int, onDark: Int, onLight: Int) {
        synchronized(nowPlayingLock) {
            colors = SendspinColors(backgroundDark, backgroundLight, onDark, onLight)
        }
        publishNowPlaying()
    }

    private fun resetNowPlaying() {
        synchronized(nowPlayingLock) {
            streamActive = false
            groupPlaying = false
            title = ""
            artist = ""
            album = ""
            artworkUrl = null
            durationMs = 0
            positionMs = 0
            speed = -1
            commands = emptySet()
            shuffle = false
            repeat = "off"
            colors = SendspinColors()
        }
        publishNowPlaying()
    }

    // Tells the UI what to show, as a config event like the other cross-component state. Only
    // changes are sent.
    private fun publishNowPlaying() {
        val old: SendspinNowPlaying
        val new: SendspinNowPlaying
        synchronized(nowPlayingLock) {
            config.sendspinPlaying = streamActive
            val hasTrack = title.isNotBlank() || artist.isNotBlank()
            new = SendspinNowPlaying(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                durationMs = durationMs,
                positionMs = positionMs,
                positionAtMs = positionAtMs,
                speed = speed,
                active = (streamActive || groupPlaying) && hasTrack,
                playing = groupPlaying && speed != 0,
                commands = commands,
                shuffle = shuffle,
                repeat = repeat,
                colors = colors,
            )
            if (new == published) return
            old = published
            published = new
        }
        config.eventBroadcaster.notifyEvent(Event("sendspinNowPlaying", old, new))
    }

    // ---- Gain / ducking ------------------------------------------------------------------

    private fun applyGain() {
        val level = volume / 100f
        val effective = when {
            muted -> 0f
            ducked -> minOf(level, duckRampGain)
            else -> level
        }
        output.setGain(effective)
    }

    private fun duckLevel(): Float = (config.duckingVolume / 50f).coerceIn(0f, 1f)

    private fun voiceHoldsFocus(): Boolean = VoicePlayerService.sInstance?.hasAudioFocus == true

    private fun duck() {
        mainHandler.removeCallbacks(unduckStep)
        ducked = true
        duckRampGain = duckLevel()
        applyGain()
    }

    // Brings the volume back gradually so music doesn't jump when the assistant finishes.
    private fun unduck() {
        if (!ducked) return
        mainHandler.removeCallbacks(unduckStep)
        unduckStep.run()
    }

    private val unduckStep = object : Runnable {
        override fun run() {
            if (!ducked) return
            duckRampGain += UNDUCK_STEP
            if (duckRampGain >= volume / 100f || duckRampGain >= 1f) {
                ducked = false
                duckRampGain = 1f
            } else {
                mainHandler.postDelayed(this, UNDUCK_INTERVAL_MS)
            }
            applyGain()
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(focusAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasAudioFocus = true
                        if (!voiceHoldsFocus()) unduck()
                    }
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        hasAudioFocus = false
                        duck()
                    }
                }
            }, mainHandler)
            .build()
            .also { focusRequest = it }
        hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
    }

    // ---- Config events -------------------------------------------------------------------

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "sendspin_preferred_server" -> {
                val h = handle
                if (h != 0L) NativeSendspin.nativeSetPreferredServer(h, preferredServerIp())
            }
            "audioOutputId" ->
                output.setPreferredDevice(AudioOutputRouter.resolvePreferredDevice(context, config))
            // A set-volume action while Sendspin is the thing playing (0-100).
            "sendspinSetVolume" -> (event.newValue as? Float)?.let { setVolumeFromDevice(it.toInt()) }
            // A transport button on the now-playing overlay.
            "sendspinCommand" -> {
                val command = event.newValue as? String ?: return
                val h = handle
                if (h != 0L) NativeSendspin.nativeSendCommand(h, command, 0)
            }
            // The assistant finished speaking: no focus callback of our own fires if we started
            // ducked (see onStreamStart), so release the duck here.
            "voicePlaybackEnded" -> mainHandler.post { if (!voiceHoldsFocus()) unduck() }
            "duckingVolume" -> mainHandler.post { if (ducked) duck() }
            // The music player was asked to play: stop Sendspin so the two don't play over each other.
            "musicPlayerStarting" -> {
                val h = handle
                val active = synchronized(nowPlayingLock) { streamActive || groupPlaying }
                if (h != 0L && active) NativeSendspin.nativeSendCommand(h, "stop", 0)
            }
            // Another VACA device heard a wake word / finished its pipeline (UDP broadcast).
            // React if we're in the sender's Sendspin group or in the sender's area.
            "remoteWakeWordDetected" -> mainHandler.post {
                if (isRelevantTo(event) && isGroupPlaying()) {
                    duck()
                    // Failsafe in case the matching "pipeline completed" broadcast is lost.
                    mainHandler.removeCallbacks(remoteUnduck)
                    mainHandler.postDelayed(remoteUnduck, REMOTE_DUCK_TIMEOUT_MS)
                }
            }
            "remotePipelineCompleted" -> mainHandler.post { if (isRelevantTo(event)) remoteUnduck.run() }
        }
    }

    private fun isRelevantTo(event: Event): Boolean =
        (event.newValue as? VacaBroadcastMessage)
            ?.isRelevantTo(config.sendspinGroupId, config.area) == true

    private fun isGroupPlaying(): Boolean = synchronized(nowPlayingLock) { groupPlaying }

    private val remoteUnduck: Runnable = object : Runnable {
        override fun run() {
            mainHandler.removeCallbacks(this)
            if (ducked && !voiceHoldsFocus()) unduck()
        }
    }

    companion object {
        private const val PREFS = "sendspin_player"
        private const val PREF_VOLUME = "volume"
        private const val PREF_MUTED = "muted"

        // Sendspin's default client port; fall back upward if it is already in use.
        const val BASE_PORT = 8928
        private const val PORT_ATTEMPTS = 4

        private const val SAMPLE_RATE = 48_000
        private const val AUDIO_BUFFER_BYTES = 1_000_000
        private const val FEEDBACK_INTERVAL_MS = 10L

        private const val UNDUCK_STEP = 0.1f
        private const val UNDUCK_INTERVAL_MS = 100L
        private const val REMOTE_DUCK_TIMEOUT_MS = 60_000L
    }
}
