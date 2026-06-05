package com.jason.mapsnap.domain.repository

import com.jason.mapsnap.data.model.DeviceUsage

interface DeviceUsageRepository {
    suspend fun getUsage(): DeviceUsage
    suspend fun updateUsage(usage: DeviceUsage)
}
