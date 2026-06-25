package com.safe.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenDetectionState(
    val isRunning: Boolean = false,
    val status: String = "",
    val lastDetectionCount: Int? = null,
    val lastUpdatedAtMillis: Long = 0L
)

object ScreenDetectionStateHolder {
    private val mutableState = MutableStateFlow(ScreenDetectionState())
    val state: StateFlow<ScreenDetectionState> = mutableState.asStateFlow()

    fun setIdle(status: String = "") {
        mutableState.value = ScreenDetectionState(
            isRunning = false,
            status = status,
            lastUpdatedAtMillis = System.currentTimeMillis()
        )
    }

    fun setRunning(status: String, lastDetectionCount: Int? = mutableState.value.lastDetectionCount) {
        mutableState.value = ScreenDetectionState(
            isRunning = true,
            status = status,
            lastDetectionCount = lastDetectionCount,
            lastUpdatedAtMillis = System.currentTimeMillis()
        )
    }
}
