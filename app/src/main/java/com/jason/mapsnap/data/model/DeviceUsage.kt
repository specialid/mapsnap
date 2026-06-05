package com.jason.mapsnap.data.model

data class DeviceUsage(
    val lastActiveDate: String = "",
    val dailyCount: Int = 0,
    val rechargedCount: Int = 0,
    val updatedAt: Long = 0
)
