package com.byeonggon.latent.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where the tabs stand in a wide window (landscape, a tablet, an unfolded foldable): a narrow one always has them at the bottom. */
enum class TabPosition { AUTO, LEFT, RIGHT, BOTTOM }

data class AppSettings(
    val syncPhotos: Boolean = true,
    val syncRaw: Boolean = true,
    val syncVideos: Boolean = true,
    val locationIntervalMinutes: Int = 5,
    val highAccuracyLocation: Boolean = true,
    val geotagMaxGapMinutes: Int = 15,
    /** How far ahead of the real time the camera's clock runs; below zero, how far behind. */
    val cameraClockOffsetMinutes: Int = 0,
    /** The folders of the phone, as MediaStore names them ("DCIM/Camera/"), whose photos and videos are listed in the library too. */
    val phoneFolders: Set<String> = emptySet(),
    /** Whether the app may use the internet, for the map. Off until the person turns it on. */
    val onlineMaps: Boolean = false,
    /** For one hand, or for the other: the side the tabs are on. [TabPosition.AUTO] is the left. */
    val tabPosition: TabPosition = TabPosition.AUTO,
) {
    /** The moment a photo taken at [cameraTime] by the camera's clock was really taken. */
    fun realTimeOf(cameraTime: Long): Long = cameraTime - cameraClockOffsetMinutes * 60_000L

    fun accepts(kind: MediaKind): Boolean = when (kind) {
        MediaKind.PHOTO -> syncPhotos
        MediaKind.RAW -> syncRaw
        MediaKind.VIDEO -> syncVideos
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences[SYNC_PHOTOS] = next.syncPhotos
            preferences[SYNC_RAW] = next.syncRaw
            preferences[SYNC_VIDEOS] = next.syncVideos
            preferences[LOCATION_INTERVAL_MINUTES] = next.locationIntervalMinutes
            preferences[HIGH_ACCURACY_LOCATION] = next.highAccuracyLocation
            preferences[GEOTAG_MAX_GAP_MINUTES] = next.geotagMaxGapMinutes
            preferences[CAMERA_CLOCK_OFFSET_MINUTES] = next.cameraClockOffsetMinutes
            preferences[PHONE_FOLDERS] = next.phoneFolders
            preferences[ONLINE_MAPS] = next.onlineMaps
            preferences[TAB_POSITION] = next.tabPosition.name
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            syncPhotos = this[SYNC_PHOTOS] ?: defaults.syncPhotos,
            syncRaw = this[SYNC_RAW] ?: defaults.syncRaw,
            syncVideos = this[SYNC_VIDEOS] ?: defaults.syncVideos,
            locationIntervalMinutes = this[LOCATION_INTERVAL_MINUTES] ?: defaults.locationIntervalMinutes,
            highAccuracyLocation = this[HIGH_ACCURACY_LOCATION] ?: defaults.highAccuracyLocation,
            geotagMaxGapMinutes = this[GEOTAG_MAX_GAP_MINUTES] ?: defaults.geotagMaxGapMinutes,
            cameraClockOffsetMinutes = this[CAMERA_CLOCK_OFFSET_MINUTES] ?: defaults.cameraClockOffsetMinutes,
            phoneFolders = this[PHONE_FOLDERS] ?: defaults.phoneFolders,
            onlineMaps = this[ONLINE_MAPS] ?: defaults.onlineMaps,
            tabPosition = this[TAB_POSITION]?.let { name -> TabPosition.entries.firstOrNull { it.name == name } } ?: defaults.tabPosition,
        )
    }

    private companion object {
        val SYNC_PHOTOS = booleanPreferencesKey("sync_photos")
        val SYNC_RAW = booleanPreferencesKey("sync_raw")
        val SYNC_VIDEOS = booleanPreferencesKey("sync_videos")
        val LOCATION_INTERVAL_MINUTES = intPreferencesKey("location_interval_minutes")
        val HIGH_ACCURACY_LOCATION = booleanPreferencesKey("high_accuracy_location")
        val GEOTAG_MAX_GAP_MINUTES = intPreferencesKey("geotag_max_gap_minutes")
        val CAMERA_CLOCK_OFFSET_MINUTES = intPreferencesKey("camera_clock_offset_minutes")
        val PHONE_FOLDERS = stringSetPreferencesKey("phone_folders")
        val ONLINE_MAPS = booleanPreferencesKey("online_maps")
        val TAB_POSITION = stringPreferencesKey("tab_position")
    }
}
