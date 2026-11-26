package com.chimerateam.offsetextractor.data.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

    /** Persisted theme mode name, one of `System`, `Light`, `Dark`, `Amoled`. */
    val themeMode: Flow<String> get() = flowOf("System")

    /** Whether the internal pass enumerates every function. */
    val includeAllFunctions: Flow<Boolean> get() = flowOf(true)

    /** Whether auto-generated signatures are produced. */
    val generateSignatures: Flow<Boolean> get() = flowOf(true)

    /** Maximum virtual slots read per class. */
    val maxSlotsPerClass: Flow<Int> get() = flowOf(4096)

    /** Whether the advisory low-memory notice is shown. */
    val lowMemoryWarnings: Flow<Boolean> get() = flowOf(DEFAULT_LOW_MEMORY_WARNINGS)

    /** Whether a downloaded APK is deleted once its library is extracted. */
    val deleteApkAfterScan: Flow<Boolean> get() = flowOf(DEFAULT_DELETE_APK_AFTER_SCAN)

    /** Hard cap on cached data in megabytes; zero disables the cap. */
    val cacheCapMb: Flow<Int> get() = flowOf(DEFAULT_CACHE_CAP_MB)

    /** Whether animations are reduced to instant transitions. */
    val reduceMotion: Flow<Boolean> get() = flowOf(false)

    /**
     * Records whether a downloaded APK is deleted after extraction.
     *
     * @param delete whether to delete it.
     */
    suspend fun setDeleteApkAfterScan(delete: Boolean) {}

    /**
     * Records the cache cap.
     *
     * @param megabytes cap in megabytes, zero to disable.
     */
    suspend fun setCacheCapMb(megabytes: Int) {}

    /**
     * Records whether animations are reduced.
     *
     * @param reduce whether to reduce them.
     */
    suspend fun setReduceMotion(reduce: Boolean) {}

    /**
     * Records the selected theme mode.
     *
     * @param mode theme mode name.
     */
    suspend fun setThemeMode(mode: String) {}

    /**
     * Records whether every function is enumerated.
     *
     * @param include whether to include all functions.
     */
    suspend fun setIncludeAllFunctions(include: Boolean) {}

    /**
     * Records whether signatures are generated.
     *
     * @param generate whether to generate signatures.
     */
    suspend fun setGenerateSignatures(generate: Boolean) {}

    /**
     * Records the slot cap.
     *
     * @param slots maximum slots per class.
     */
    suspend fun setMaxSlotsPerClass(slots: Int) {}

    /**
     * Records whether the low-memory notice is shown.
     *
     * @param enabled whether to show it.
     */
    suspend fun setLowMemoryWarnings(enabled: Boolean) {}
}

/**
 * Whether the low-memory notice is shown by default.
 *
 * It is advisory only: it changes nothing about how much memory the app uses,
 * and Android reports pressure on a healthy device often enough that showing it
 * by default trains the user to dismiss it. Flip this one constant to change
 * the default for everyone.
 */
const val DEFAULT_LOW_MEMORY_WARNINGS = false

/**
 * Whether a downloaded APK is deleted once its library is extracted.
 *
 * A scan of a 1 GB build leaves a copy that is never read again, and three
 * other copies follow it (library, table, shards), so keeping the APK is what
 * fills the device. The user's own file is never touched; only APKs the APKs
 * page downloaded into the cache are deleted.
 */
const val DEFAULT_DELETE_APK_AFTER_SCAN = true

/**
 * Default cap on cached data, in megabytes.
 *
 * Two gigabytes is one scan of the largest build plus its archive, which is the
 * most that is ever useful at once. Oldest files are removed first.
 */
const val DEFAULT_CACHE_CAP_MB = 2048

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

    override val themeMode: Flow<String> =
        store.data.map { it[THEME_MODE] ?: "System" }

    override val includeAllFunctions: Flow<Boolean> =
        store.data.map { it[INCLUDE_ALL_FUNCTIONS] ?: true }

    override val generateSignatures: Flow<Boolean> =
        store.data.map { it[GENERATE_SIGNATURES] ?: true }

    override val maxSlotsPerClass: Flow<Int> =
        store.data.map { it[MAX_SLOTS_PER_CLASS] ?: 4096 }

    override suspend fun setThemeMode(mode: String) {
        store.edit { it[THEME_MODE] = mode }
    }

    override suspend fun setIncludeAllFunctions(include: Boolean) {
        store.edit { it[INCLUDE_ALL_FUNCTIONS] = include }
    }

    override suspend fun setGenerateSignatures(generate: Boolean) {
        store.edit { it[GENERATE_SIGNATURES] = generate }
    }

    override suspend fun setMaxSlotsPerClass(slots: Int) {
        store.edit { it[MAX_SLOTS_PER_CLASS] = slots.coerceIn(64, 4096) }
    }

    override val lowMemoryWarnings: Flow<Boolean> =
        store.data.map { it[LOW_MEMORY_WARNINGS] ?: DEFAULT_LOW_MEMORY_WARNINGS }

    override suspend fun setLowMemoryWarnings(enabled: Boolean) {
        store.edit { it[LOW_MEMORY_WARNINGS] = enabled }
    }

    override val deleteApkAfterScan: Flow<Boolean> =
        store.data.map { it[DELETE_APK_AFTER_SCAN] ?: DEFAULT_DELETE_APK_AFTER_SCAN }

    override suspend fun setDeleteApkAfterScan(delete: Boolean) {
        store.edit { it[DELETE_APK_AFTER_SCAN] = delete }
    }

    override val cacheCapMb: Flow<Int> =
        store.data.map { it[CACHE_CAP_MB] ?: DEFAULT_CACHE_CAP_MB }

    override suspend fun setCacheCapMb(megabytes: Int) {
        store.edit { it[CACHE_CAP_MB] = megabytes.coerceIn(0, 64 * 1024) }
    }

    override val reduceMotion: Flow<Boolean> =
        store.data.map { it[REDUCE_MOTION] ?: false }

    override suspend fun setReduceMotion(reduce: Boolean) {
        store.edit { it[REDUCE_MOTION] = reduce }
    }

    /** Reads the stored build identifier once, for use outside a flow. */
    suspend fun currentBuildId(): String = lastBuildId.first()

    private companion object {
        val LAST_BUILD_ID = stringPreferencesKey("last_build_id")
        val FORCE_RESCAN = booleanPreferencesKey("force_rescan")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val INCLUDE_ALL_FUNCTIONS = booleanPreferencesKey("include_all_functions")
        val GENERATE_SIGNATURES = booleanPreferencesKey("generate_signatures")
        val MAX_SLOTS_PER_CLASS = intPreferencesKey("max_slots_per_class")
        val LOW_MEMORY_WARNINGS = booleanPreferencesKey("low_memory_warnings")
        val DELETE_APK_AFTER_SCAN = booleanPreferencesKey("delete_apk_after_scan")
        val CACHE_CAP_MB = intPreferencesKey("cache_cap_mb")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
    }
}

/** Application-wide DataStore instance; one per process, as DataStore requires. */
private val Context.scanDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "scanner_preferences"
)

/**
 * Builds the production preference store.
 *
 * @param context any context; only the application context is used.
 * @return the process-wide preference store.
 */
fun scanPreferences(context: Context): ScanPreferences =
    DataStoreScanPreferences(context.applicationContext.scanDataStore)
