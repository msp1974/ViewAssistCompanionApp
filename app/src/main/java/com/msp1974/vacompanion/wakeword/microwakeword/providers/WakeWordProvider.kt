package com.msp1974.vacompanion.wakeword.microwakeword.providers

import com.msp1974.vacompanion.wakeword.microwakeword.models.WakeWordWithId

interface WakeWordProvider {
    suspend fun get(): List<WakeWordWithId>
}