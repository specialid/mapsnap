package com.jason.mapsnap.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jason.mapsnap.data.model.SavedRouteEntity
import com.jason.mapsnap.domain.model.SavedRoute
import com.jason.mapsnap.domain.repository.SavedRouteRepository
import com.naver.maps.geometry.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SavedRouteRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SavedRouteRepository {

    private val prefs by lazy { context.getSharedPreferences("mapsnap_saved_routes_prefs", Context.MODE_PRIVATE) }
    private val gson = Gson()
    private val listType = object : TypeToken<List<SavedRouteEntity>>() {}.type

    private fun readEntities(): List<SavedRouteEntity> {
        val json = prefs.getString("routes", null) ?: return emptyList()
        return runCatching { gson.fromJson<List<SavedRouteEntity>>(json, listType) }.getOrDefault(emptyList())
    }

    private fun writeEntities(entities: List<SavedRouteEntity>) {
        prefs.edit().putString("routes", gson.toJson(entities)).apply()
    }

    private fun SavedRouteEntity.toDomain() = SavedRoute(
        id = id,
        name = name,
        createdAt = createdAt,
        routeStart = LatLng(startLat, startLon),
        routeEnd = LatLng(endLat, endLon),
        routeMarkers = markerLats.indices.map { LatLng(markerLats[it], markerLons[it]) },
        snappedRoute = routeLats.indices.map { LatLng(routeLats[it], routeLons[it]) },
        distanceMeters = distanceMeters
    )

    private fun SavedRoute.toEntity() = SavedRouteEntity(
        id = id,
        name = name,
        createdAt = createdAt,
        startLat = routeStart.latitude,
        startLon = routeStart.longitude,
        endLat = routeEnd.latitude,
        endLon = routeEnd.longitude,
        markerLats = routeMarkers.map { it.latitude },
        markerLons = routeMarkers.map { it.longitude },
        routeLats = snappedRoute.map { it.latitude },
        routeLons = snappedRoute.map { it.longitude },
        distanceMeters = distanceMeters
    )

    override suspend fun getAll(): List<SavedRoute> = withContext(Dispatchers.IO) {
        readEntities().map { it.toDomain() }.sortedByDescending { it.createdAt }
    }

    override suspend fun save(route: SavedRoute): Unit = withContext(Dispatchers.IO) {
        writeEntities(readEntities() + route.toEntity())
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        writeEntities(readEntities().filterNot { it.id == id })
    }
}
