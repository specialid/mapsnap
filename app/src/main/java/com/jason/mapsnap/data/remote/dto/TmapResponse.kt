package com.jason.mapsnap.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class TmapResponse(
    @SerializedName("type") val type: String,
    @SerializedName("features") val features: List<TmapFeature>?
)

data class TmapFeature(
    @SerializedName("type") val type: String,
    @SerializedName("geometry") val geometry: TmapGeometry,
    @SerializedName("properties") val properties: TmapProperties
)

data class TmapGeometry(
    @SerializedName("type") val type: String,
    @SerializedName("coordinates") val coordinates: JsonElement
)

data class TmapProperties(
    @SerializedName("totalDistance") val totalDistance: Double?,
    @SerializedName("totalTime") val totalTime: Double?,
    @SerializedName("index") val index: Int?,
    @SerializedName("pointIndex") val pointIndex: Int?,
    @SerializedName("lineIndex") val lineIndex: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("description") val description: String?
)
