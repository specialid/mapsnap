package com.jason.mapsnap.data.remote.dto

import com.google.gson.annotations.SerializedName

data class OrsResponse(
    @SerializedName("type") val type: String,
    @SerializedName("features") val features: List<OrsFeature>?
)

data class OrsFeature(
    @SerializedName("type") val type: String,
    @SerializedName("geometry") val geometry: OrsGeometry
)

data class OrsGeometry(
    @SerializedName("type") val type: String,
    // LineString 좌표 배열: [[lng, lat], ...]
    @SerializedName("coordinates") val coordinates: List<List<Double>>
)
