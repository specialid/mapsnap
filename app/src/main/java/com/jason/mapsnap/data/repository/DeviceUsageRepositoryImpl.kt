package com.jason.mapsnap.data.repository

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.jason.mapsnap.data.model.DeviceUsage
import com.jason.mapsnap.domain.repository.DeviceUsageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.inject.Inject

class DeviceUsageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceUsageRepository {

    private var localFallbackUsage: com.jason.mapsnap.data.model.DeviceUsage = com.jason.mapsnap.data.model.DeviceUsage()

    private fun getDatabase(): FirebaseDatabase? {
        return try {
            FirebaseDatabase.getInstance("https://map-snap-b0438-default-rtdb.asia-southeast1.firebasedatabase.app")
        } catch (e: Exception) {
            Log.e("DeviceUsageRepo", "FirebaseDatabase not initialized: ${e.message}")
            null
        }
    }

    override suspend fun getUsage(): DeviceUsage = withContext(Dispatchers.IO) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val hashedId = hashSha256(deviceId)
        
        val db = getDatabase()
        if (db == null) {
            Log.w("DeviceUsageRepo", "Database is null, returning local fallback")
            return@withContext localFallbackUsage
        }
        
        try {
            val usage = withTimeoutOrNull(3000L) {
                val snapshot = db.getReference("device_usage/$hashedId").get().await()
                snapshot.getValue(DeviceUsage::class.java)
            }
            if (usage != null) {
                localFallbackUsage = usage
                usage
            } else {
                Log.w("DeviceUsageRepo", "Timeout fetching usage, returning local fallback")
                localFallbackUsage
            }
        } catch (e: Exception) {
            Log.w("DeviceUsageRepo", "Error fetching usage: ${e.message}, returning local fallback", e)
            localFallbackUsage
        }
    }

    override suspend fun updateUsage(usage: DeviceUsage): Unit = withContext(Dispatchers.IO) {
        localFallbackUsage = usage
        
        val db = getDatabase()
        if (db == null) {
            Log.w("DeviceUsageRepo", "Database is null, cannot update database")
            return@withContext
        }
        
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val hashedId = hashSha256(deviceId)
        
        try {
            val result = withTimeoutOrNull(3000L) {
                db.getReference("device_usage/$hashedId").setValue(usage).await()
                true
            }
            if (result == null) {
                Log.w("DeviceUsageRepo", "Timeout updating usage in database")
            }
        } catch (e: Exception) {
            Log.e("DeviceUsageRepo", "Error updating usage in database: ${e.message}", e)
        }
    }

    private fun hashSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { String.format("%02x", it) }
    }
}
