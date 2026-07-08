package com.jason.mapsnap.domain.repository

import com.jason.mapsnap.domain.model.AppSettings

interface SettingsRepository {
    suspend fun getSettings(): AppSettings
    suspend fun saveSettings(settings: AppSettings)
}
