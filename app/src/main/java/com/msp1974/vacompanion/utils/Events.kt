package com.msp1974.vacompanion.utils

data class Event(val eventName: String, val oldValue: Any, val newValue: Any)

interface EventListener {
    fun onEventTriggered(event: Event)
}

class EventNotifier {

    private val listeners: MutableSet<EventListener> = HashSet()

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