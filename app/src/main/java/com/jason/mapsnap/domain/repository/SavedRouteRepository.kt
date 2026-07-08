package com.jason.mapsnap.domain.repository

import com.jason.mapsnap.domain.model.SavedRoute

interface SavedRouteRepository {
    /** 최신순(createdAt 내림차순)으로 저장된 경로 목록을 반환한다 */
    suspend fun getAll(): List<SavedRoute>
    suspend fun save(route: SavedRoute)
    suspend fun delete(id: String)
}
