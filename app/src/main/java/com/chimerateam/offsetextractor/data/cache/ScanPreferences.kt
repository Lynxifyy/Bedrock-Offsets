package com.chimerateam.offsetextractor.data.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Persistent scanner preferences. */
interface ScanPreferences {

    /** Build identifier of the most recent successful scan. */
    val lastBuildId: Flow<String>

    /** Whether a cached result is ignored and the scan is repeated. */
    val forceRescan: Flow<Boolean>

    /**
     * Records the build identifier of the most recent scan.
     *
     * @param buildId build identifier, or an empty string to forget it.
     */
    suspend fun setLastBuildId(buildId: String)

    /**
     * Records whether scans must ignore the cache.
     *
     * @param force whether to always rescan.
     */
    suspend fun setForceRescan(force: Boolean)
}

/** File-backed [ScanPreferences] on top of DataStore. */
class DataStoreScanPreferences(private val store: DataStore<Preferences>) : ScanPreferences {

    override val lastBuildId: Flow<String> =
        store.data.map { it[LAST_BUILD_ID].orEmpty() }

    override val forceRescan: Flow<Boolean> =
        store.data.map { it[FORCE_RESCAN] ?: false }

    override suspend fun setLastBuildId(buildId: String) {
        store.edit { it[LAST_BUILD_ID] = buildId }
    }

    override suspend fun setForceRescan(force: Boolean) {
        store.edit { it[FORCE_RESCAN] = force }
    }

    /** Reads the stored build identifier once, for use outside a flow. */
    suspend fun currentBuildId(): String = lastBuildId.first()

    private companion object {
        val LAST_BUILD_ID = stringPreferencesKey("last_build_id")
        val FORCE_RESCAN = booleanPreferencesKey("force_rescan")
    }
}

/** Application-wide DataStore instance; one per process, as DataStore requires. */
private val Context.scanDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "scanner_preferences",
)

/**
 * Builds the production preference store.
 *
 * @param context any context; only the application context is used.
 * @return the process-wide preference store.
 */
fun scanPreferences(context: Context): ScanPreferences =
    DataStoreScanPreferences(context.applicationContext.scanDataStore)
