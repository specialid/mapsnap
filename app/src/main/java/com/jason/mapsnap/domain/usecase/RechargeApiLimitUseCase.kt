package com.jason.mapsnap.domain.usecase

import com.jason.mapsnap.data.model.DeviceUsage
import com.jason.mapsnap.domain.repository.DeviceUsageRepository
import javax.inject.Inject

class RechargeApiLimitUseCase @Inject constructor(
    private val repository: DeviceUsageRepository
) {
    suspend operator fun invoke() {
        val usage = repository.getUsage()
        val updatedUsage = usage.copy(
            rechargedCount = usage.rechargedCount + 10,
            updatedAt = System.currentTimeMillis()
        )
        repository.updateUsage(updatedUsage)
    }
}
