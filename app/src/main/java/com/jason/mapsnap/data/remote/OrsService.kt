package com.jason.mapsnap.data.remote

import com.jason.mapsnap.data.remote.dto.OrsRequest
import com.jason.mapsnap.data.remote.dto.OrsResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OrsService {
    @POST("v2/directions/foot-walking/geojson")
    suspend fun getPedestrianRoute(
        @Header("Authorization") apiKey: String,
        @Body request: OrsRequest
    ): OrsResponse
}
