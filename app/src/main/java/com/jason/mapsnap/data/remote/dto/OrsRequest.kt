package com.jason.mapsnap.data.remote.dto

import com.google.gson.annotations.SerializedName

data class OrsRequest(
    // ORS 좌표 순서는 [경도, 위도] — T-Map(startX/Y)과 반대이므로 변환 시 주의
    @SerializedName("coordinates") val coordinates: List<List<Double>>
)
