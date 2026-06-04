package com.msp1974.vacompanion.satellite

data class VacaTimer(
    val id: String,
    val name: String? = null,
    val durationSeconds: Int,
    val remainingSeconds: Int,
    val updatedAtElapsedRealtime: Long,
    val isActive: Boolean = true
)

data class VacaTimerUiState(
    val id: String,
    val name: String? = null,
    val durationSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val updatedAtElapsedRealtime: Long = 0L,
    val expiresAtElapsedRealtime: Long = 0L,
    val isFinished: Boolean = false
)

fun VacaTimer.toUiState(isFinished: Boolean = false): VacaTimerUiState {
    return VacaTimerUiState(
        id = id,
        name = name,
        durationSeconds = durationSeconds,
        remainingSeconds = if (isFinished) 0 else remainingSeconds,
        updatedAtElapsedRealtime = updatedAtElapsedRealtime,
        expiresAtElapsedRealtime = updatedAtElapsedRealtime + (remainingSeconds * 1000L),
        isFinished = isFinished
    )
}
