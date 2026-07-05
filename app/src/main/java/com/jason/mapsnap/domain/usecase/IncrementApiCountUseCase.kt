package com.jason.mapsnap.domain.usecase

import com.jason.mapsnap.data.model.DeviceUsage
import com.jason.mapsnap.domain.repository.DeviceUsageRepository
import javax.inject.Inject

class IncrementApiCountUseCase @Inject constructor(
    private val repository: DeviceUsageRepository
) {
    /** @param count 실제로 발생한 T-Map HTTP 호출(청크) 수만큼 원자적으로 증가 */
    suspend operator fun invoke(count: Int = 1) {
        repeat(count) {
            repository.incrementDailyCount()
        }
    }
}
