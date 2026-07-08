package com.jason.mapsnap.data.repository

import android.content.Context
import com.jason.mapsnap.domain.model.AppSettings
import com.jason.mapsnap.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val prefs by lazy { context.getSharedPreferences("mapsnap_settings_prefs", Context.MODE_PRIVATE) }

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        val defaults = AppSettings()
        AppSettings(
            markerIntervalMeters = prefs.getFloat("markerIntervalMeters", defaults.markerIntervalMeters.toFloat()).toDouble(),
            epsilonDrawnDeg = prefs.getFloat("epsilonDrawnDeg", defaults.epsilonDrawnDeg.toFloat()).toDouble(),
            epsilonRouteDeg = prefs.getFloat("epsilonRouteDeg", defaults.epsilonRouteDeg.toFloat()).toDouble(),
            includeTimestamps = prefs.getBoolean("includeTimestamps", defaults.includeTimestamps),
            runningPaceSecPerKm = prefs.getInt("runningPaceSecPerKm", defaults.runningPaceSecPerKm)
        )
    }

    override suspend fun saveSettings(settings: AppSettings): Unit = withContext(Dispatchers.IO) {
        prefs.edit()
            .putFloat("markerIntervalMeters", settings.markerIntervalMeters.toFloat())
            .putFloat("epsilonDrawnDeg", settings.epsilonDrawnDeg.toFloat())
            .putFloat("epsilonRouteDeg", settings.epsilonRouteDeg.toFloat())
            .putBoolean("includeTimestamps", settings.includeTimestamps)
            .putInt("runningPaceSecPerKm", settings.runningPaceSecPerKm)
            .apply()
    }
}
