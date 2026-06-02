package com.jason.mapsnap.data.tracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiCallTracker @Inject constructor() {
    private val _tmapCount = MutableStateFlow(0)
    val tmapCount: StateFlow<Int> = _tmapCount.asStateFlow()

    private val _naverCount = MutableStateFlow(0)
    val naverCount: StateFlow<Int> = _naverCount.asStateFlow()

    fun incrementTmap() {
        _tmapCount.update { it + 1 }
    }

    fun incrementNaver() {
        _naverCount.update { it + 1 }
    }
}
