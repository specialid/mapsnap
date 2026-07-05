package com.jason.mapsnap.domain.repository

import com.jason.mapsnap.data.model.DeviceUsage

interface DeviceUsageRepository {
    suspend fun getUsage(): DeviceUsage
    suspend fun updateUsage(usage: DeviceUsage)

    /** dailyCount를 원자적으로 1 증가시키고 갱신된 사용량을 반환한다 (동시 호출 시 증분 유실 방지) */
    suspend fun incrementDailyCount(): DeviceUsage
}
