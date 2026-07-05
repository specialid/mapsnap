package com.jason.mapsnap.data.repository

import android.content.Context
import android.provider.Settings
import timber.log.Timber
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.jason.mapsnap.data.model.DeviceUsage
import com.jason.mapsnap.domain.repository.DeviceUsageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DeviceUsageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceUsageRepository {

    private val prefs by lazy { context.getSharedPreferences("mapsnap_usage_prefs", Context.MODE_PRIVATE) }

    // 재시작 후에도 한도 우회를 막기 위해 로컬 영속 저장소에서 초기값을 로드한다
    private var localFallbackUsage: DeviceUsage = loadLocalUsage()

    private fun loadLocalUsage(): DeviceUsage = DeviceUsage(
        lastActiveDate = prefs.getString("lastActiveDate", "") ?: "",
        dailyCount = prefs.getInt("dailyCount", 0),
        rechargedCount = prefs.getInt("rechargedCount", 0),
        updatedAt = prefs.getLong("updatedAt", 0L)
    )

    private fun saveLocalUsage(usage: DeviceUsage) {
        prefs.edit()
            .putString("lastActiveDate", usage.lastActiveDate)
            .putInt("dailyCount", usage.dailyCount)
            .putInt("rechargedCount", usage.rechargedCount)
            .putLong("updatedAt", usage.updatedAt)
            .apply()
    }

    private fun getDatabase(): FirebaseDatabase? {
        return try {
            FirebaseDatabase.getInstance("https://map-snap-b0438-default-rtdb.asia-southeast1.firebasedatabase.app")
        } catch (e: Exception) {
            Timber.e("FirebaseDatabase not initialized: ${e.message}")
            null
        }
    }

    override suspend fun getUsage(): DeviceUsage = withContext(Dispatchers.IO) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val hashedId = hashSha256(deviceId)

        val db = getDatabase()
        if (db == null) {
            Timber.w("Database is null, returning local fallback")
            return@withContext localFallbackUsage
        }

        try {
            val usage = withTimeoutOrNull(3000L) {
                val snapshot = db.getReference("device_usage/$hashedId").get().await()
                snapshot.getValue(DeviceUsage::class.java)
            }
            if (usage != null) {
                localFallbackUsage = usage
                saveLocalUsage(usage)
                usage
            } else {
                Timber.w("Timeout fetching usage, returning local fallback")
                localFallbackUsage
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching usage: ${e.message}, returning local fallback")
            localFallbackUsage
        }
    }

    override suspend fun updateUsage(usage: DeviceUsage): Unit = withContext(Dispatchers.IO) {
        localFallbackUsage = usage
        saveLocalUsage(usage)

        val db = getDatabase()
        if (db == null) {
            Timber.w("Database is null, cannot update database")
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
                Timber.w("Timeout updating usage in database")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating usage in database: ${e.message}")
        }
    }

    override suspend fun incrementDailyCount(): DeviceUsage = withContext(Dispatchers.IO) {
        val db = getDatabase()
        if (db == null) {
            Timber.w("Database is null, incrementing local fallback only")
            return@withContext incrementLocalFallback()
        }

        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val hashedId = hashSha256(deviceId)
        val ref = db.getReference("device_usage/$hashedId")

        try {
            val result = withTimeoutOrNull(3000L) { runIncrementTransaction(ref) }
            if (result != null) {
                localFallbackUsage = result
                saveLocalUsage(result)
                result
            } else {
                Timber.w("Transaction timeout, incrementing local fallback only")
                incrementLocalFallback()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error incrementing usage in database: ${e.message}, incrementing local fallback only")
            incrementLocalFallback()
        }
    }

    private fun incrementLocalFallback(): DeviceUsage {
        val updated = localFallbackUsage.copy(
            dailyCount = localFallbackUsage.dailyCount + 1,
            updatedAt = System.currentTimeMillis()
        )
        localFallbackUsage = updated
        saveLocalUsage(updated)
        return updated
    }

    private suspend fun runIncrementTransaction(
        ref: com.google.firebase.database.DatabaseReference
    ): DeviceUsage = suspendCancellableCoroutine { cont ->
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val existing = currentData.getValue(DeviceUsage::class.java) ?: DeviceUsage()
                currentData.value = existing.copy(
                    dailyCount = existing.dailyCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) {
                    cont.resumeWithException(error.toException())
                } else if (!committed || snapshot == null) {
                    cont.resumeWithException(IllegalStateException("Transaction not committed"))
                } else {
                    cont.resume(snapshot.getValue(DeviceUsage::class.java) ?: DeviceUsage())
                }
            }
        })
    }

    private fun hashSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { String.format("%02x", it) }
    }
}
