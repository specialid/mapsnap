package com.jason.mapsnap.domain.model

data class GpxExportOptions(
    val includeTimestamps: Boolean = false,
    val paceSecPerKm: Int = 360,
    val startTimeMillis: Long = System.currentTimeMillis()
)
