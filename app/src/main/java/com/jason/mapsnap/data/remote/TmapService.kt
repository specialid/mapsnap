package com.jason.mapsnap.data.remote

import com.jason.mapsnap.data.remote.dto.TmapRequest
import com.jason.mapsnap.data.remote.dto.TmapResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface TmapService {
    @POST("tmap/routes/pedestrian")
    suspend fun getPedestrianRoute(
        @Header("appKey") appKey: String,
        @Query("version") version: Int = 1,
        @Body request: TmapRequest
    ): TmapResponse
}
