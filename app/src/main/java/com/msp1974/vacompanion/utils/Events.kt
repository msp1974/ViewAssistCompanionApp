package com.msp1974.vacompanion.utils

import java.util.concurrent.CopyOnWriteArraySet

data class Event(val eventName: String, val oldValue: Any, val newValue: Any)

interface EventListener {
    fun onEventTriggered(event: Event)
}

class EventNotifier {

    // Listeners are added/removed from arbitrary coroutine dispatchers (satellite pipeline, UI,
    // camera, mic controller, etc.) while notifyEvent() may be iterating on another thread at the
    // same time - a plain HashSet throws ConcurrentModificationException in that case (seen in
    // production: wake word change -> SatelliteWakeWorkHandler.stop() -> sendDiagnostics() ->
    // notifyEvent() racing another thread's addListener/removeListener). CopyOnWriteArraySet
    // makes add/remove/iterate all safe to interleave across threads.
    private val listeners: MutableSet<EventListener> = CopyOnWriteArraySet()

    fun addListener(eventListener: EventListener) {
        listeners.add(eventListener)
    }

    fun removeListener(eventListener: EventListener) {
        listeners.remove(eventListener)
    }

    fun notifyEvent(event: Event) {
        listeners.forEach {
            it.onEventTriggered(event)
        }
    }
}