package com.jason.mapsnap.data.remote.dto

import com.google.gson.annotations.SerializedName

data class OsrmResponse(
    @SerializedName("code") val code: String,
    @SerializedName("routes") val routes: List<OsrmRoute>?
)

data class OsrmRoute(
    @SerializedName("geometry") val geometry: OsrmGeometry,
    @SerializedName("distance") val distance: Double,
    @SerializedName("duration") val duration: Double
)

data class OsrmGeometry(
    @SerializedName("type") val type: String,
    @SerializedName("coordinates") val coordinates: List<List<Double>>  // [[lng, lat], ...]
)
