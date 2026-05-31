package com.jason.mapsnap.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TmapRequest(
    @SerializedName("startX") val startX: Double,
    @SerializedName("startY") val startY: Double,
    @SerializedName("endX") val endX: Double,
    @SerializedName("endY") val endY: Double,
    @SerializedName("passList") val passList: String?,
    @SerializedName("reqCoordType") val reqCoordType: String = "WGS84GEO",
    @SerializedName("resCoordType") val resCoordType: String = "WGS84GEO",
    @SerializedName("searchOption") val searchOption: String = "0", // 0: 추천 경로 — passList와 함께 쓸 때 선 추종 안정성이 높음
    @SerializedName("startName") val startName: String = "Start",
    @SerializedName("endName") val endName: String = "End"
)
