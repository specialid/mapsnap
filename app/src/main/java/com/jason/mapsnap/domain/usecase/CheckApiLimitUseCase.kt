package com.jason.mapsnap.domain.usecase

import com.jason.mapsnap.data.model.DeviceUsage
import com.jason.mapsnap.domain.repository.DeviceUsageRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class CheckApiLimitUseCase @Inject constructor(
    private val repository: DeviceUsageRepository
) {
    sealed interface CheckResult {
        data class Allowed(val usage: DeviceUsage) : CheckResult
        data class Blocked(val usage: DeviceUsage) : CheckResult
    }

    suspend operator fun invoke(): CheckResult {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        var usage = repository.getUsage()

        if (usage.lastActiveDate != today) {
            usage = DeviceUsage(
                lastActiveDate = today,
                dailyCount = 0,
                rechargedCount = 0,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateUsage(usage)
        }

        val limit = DeviceUsage.DAILY_BASE_LIMIT + usage.rechargedCount
        return if (usage.dailyCount < limit) {
            CheckResult.Allowed(usage)
        } else {
            CheckResult.Blocked(usage)
        }
    }
}
