package com.jason.mapsnap.domain.usecase

import com.jason.mapsnap.data.model.DeviceUsage
import com.jason.mapsnap.domain.repository.DeviceUsageRepository
import javax.inject.Inject

class IncrementApiCountUseCase @Inject constructor(
    private val repository: DeviceUsageRepository
) {
    suspend operator fun invoke() {
        val usage = repository.getUsage()
        val updatedUsage = usage.copy(
            dailyCount = usage.dailyCount + 1,
            updatedAt = System.currentTimeMillis()
        )
        repository.updateUsage(updatedUsage)
    }
}
