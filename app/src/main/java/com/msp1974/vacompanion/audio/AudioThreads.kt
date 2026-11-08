package com.msp1974.vacompanion.audio

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dispatchers whose threads run at audio scheduling priority. Without it the microphone read loop
 * and the wake word model compete as equals with everything else the app does, so on a slow device
 * a busy WebView (several camera feeds decoding in software, say) starves them: the microphone
 * overruns and the wake word stops triggering.
 */
object AudioThreads {
    /** The microphone read loop. A late read loses audio, so this gets the highest priority. */
    val capture: CoroutineDispatcher by lazy { dispatcher("mic-capture", Process.THREAD_PRIORITY_URGENT_AUDIO) }

    /** The wake word models running on the captured audio. */
    val inference: CoroutineDispatcher by lazy { dispatcher("wake-word", Process.THREAD_PRIORITY_AUDIO) }

    // A cached pool rather than a single thread: an engine that is slow to stop must not hold up the
    // one that replaces it. Idle threads are dropped, so nothing is kept while the microphone is closed.
    private fun dispatcher(name: String, priority: Int): CoroutineDispatcher =
        Executors.newCachedThreadPool { task ->
            Thread({
                Process.setThreadPriority(priority)
                task.run()
            }, name)
        }.asCoroutineDispatcher()
}
