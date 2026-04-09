package com.safe.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenDetectionState(
    val isRunning: Boolean = false,
    val status: String = "屏幕检测未启动",
    val lastDetectionCount: Int? = null,
    val lastUpdatedAtMillis: Long = 0L
)

object ScreenDetectionStateHolder {
    private val mutableState = MutableStateFlow(ScreenDetectionState())
    val state: StateFlow<ScreenDetectionState> = mutableState.asStateFlow()

    fun setIdle(status: String = "屏幕检测未启动") {
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
