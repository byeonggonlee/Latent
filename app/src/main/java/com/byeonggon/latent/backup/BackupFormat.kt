package com.byeonggon.latent.backup

import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.GridSize
import com.byeonggon.latent.data.TabPosition
import com.byeonggon.latent.data.ThemeMode
import com.byeonggon.latent.data.db.AlbumEntity
import com.byeonggon.latent.data.db.AlbumItemEntity
import com.byeonggon.latent.data.db.EditVersionEntity
import com.byeonggon.latent.data.db.FilterEntity
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import com.byeonggon.latent.data.db.LutEntity
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.data.db.SyncRecordEntity
import com.byeonggon.latent.data.db.SyncedFolderEntity
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The layout of a backup file: a zip of JSON and JSON Lines entries and the files that edits refer to.
 * It is the app's own format, apart from the database's, so that it outlives changes to the database.
 * docs/BACKUP_FORMAT.md describes it; a change here must be written down there.
 */
object BackupFormat {
    /** What `manifest.json` says a file is, to tell a backup from another zip that happens to hold a manifest. */
    const val MARKER = "latent-backup"

    /** The version this app writes, and the newest it can read. Older versions stay readable. */
    const val VERSION = 1

    const val MIME_TYPE = "application/zip"

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val MEDIA = "media.jsonl"
    const val ALBUMS = "albums.jsonl"
    const val ALBUM_ITEMS = "album_items.jsonl"
    const val VERSIONS = "versions.jsonl"
    const val FILTERS = "filters.jsonl"
    const val LUTS = "luts.jsonl"
    const val SYNC_RECORDS = "sync_records.jsonl"
    const val SYNCED_FOLDERS = "synced_folders.jsonl"
    const val LOCATIONS = "locations.jsonl"

    /** `luts/<id>.lut`: a LUT's table as LutStore keeps it. */
    val LUT_FILE = Regex("luts/([0-9a-f]{32})\\.lut")

    /** `masks/<id>.mask`: a detected mask's outline as MaskStore keeps it. */
    val MASK_FILE = Regex("masks/([0-9a-f]{32})\\.mask")

    fun lutEntry(id: String) = "luts/$id.lut"

    fun maskEntry(id: String) = "masks/$id.mask"

    /** `latent-backup-20261020-1530.zip`, in the phone's time. */
    fun fileName(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "latent-backup-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(at.atZone(zone)) + ".zip"
}

/** The app that made a backup. */
data class AppIdentity(val packageName: String, val versionName: String, val versionCode: Long)

/** How many of each thing a backup holds. */
data class BackupCounts(
    val media: Int = 0,
    val albums: Int = 0,
    val albumItems: Int = 0,
    val versions: Int = 0,
    val filters: Int = 0,
    val luts: Int = 0,
    val lutFiles: Int = 0,
    val masks: Int = 0,
    val syncRecords: Int = 0,
    val syncedFolders: Int = 0,
    val locations: Int = 0,
) {
    internal fun toJson() = JsonObject().apply {
        addProperty("media", media)
        addProperty("albums", albums)
        addProperty("albumItems", albumItems)
        addProperty("versions", versions)
        addProperty("filters", filters)
        addProperty("luts", luts)
        addProperty("lutFiles", lutFiles)
        addProperty("masks", masks)
        addProperty("syncRecords", syncRecords)
        addProperty("syncedFolders", syncedFolders)
        addProperty("locations", locations)
    }

    internal companion object {
        fun fromJson(json: JsonObject?): BackupCounts {
            fun count(name: String) = json?.optLong(name)?.toInt() ?: 0
            return BackupCounts(
                count("media"), count("albums"), count("albumItems"), count("versions"), count("filters"), count("luts"),
                count("lutFiles"), count("masks"), count("syncRecords"), count("syncedFolders"), count("locations"),
            )
        }
    }
}

/** What `manifest.json` says. */
data class BackupManifest(
    val formatVersion: Int,
    val createdAt: Instant,
    val app: AppIdentity,
    val counts: BackupCounts,
) {
    internal fun toJson() = JsonObject().apply {
        addProperty("format", BackupFormat.MARKER)
        addProperty("formatVersion", formatVersion)
        addProperty("createdAt", createdAt.toString())
        add(
            "app",
            JsonObject().apply {
                addProperty("package", app.packageName)
                addProperty("versionName", app.versionName)
                addProperty("versionCode", app.versionCode)
            },
        )
        add("counts", counts.toJson())
    }

    internal companion object {
        /** Throws [NotABackupException] for the manifest of something else, [NewerBackupException] for one this app is too old for. */
        fun fromJson(json: JsonObject): BackupManifest {
            if (json.optString("format") != BackupFormat.MARKER) throw NotABackupException()
            val version = json.optLong("formatVersion")?.toInt() ?: throw NotABackupException()
            if (version > BackupFormat.VERSION) throw NewerBackupException(version)
            if (version < 1) throw NotABackupException()
            val app = json.get("app")?.takeIf { it.isJsonObject }?.asJsonObject
            return BackupManifest(
                formatVersion = version,
                createdAt = json.optString("createdAt")?.let(Instant::parse) ?: Instant.EPOCH,
                app = AppIdentity(
                    packageName = app?.optString("package").orEmpty(),
                    versionName = app?.optString("versionName").orEmpty(),
                    versionCode = app?.optLong("versionCode") ?: 0,
                ),
                counts = BackupCounts.fromJson(json.get("counts")?.takeIf { it.isJsonObject }?.asJsonObject),
            )
        }
    }
}

/** Why a file cannot be restored. Whatever the reason, nothing on the phone was changed. */
sealed class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Not a zip, or a zip without a Latent manifest. */
class NotABackupException(cause: Throwable? = null) : BackupException("Not a Latent backup", cause)

/** A backup whose entries cannot be read: cut short, changed, or written by something else. */
class DamagedBackupException(entry: String, line: Int? = null, cause: Throwable? = null) :
    BackupException("Damaged backup: $entry" + (line?.let { ", line $it" } ?: ""), cause)

/** Written by a newer app, in a format this one does not know. */
class NewerBackupException(val version: Int) : BackupException("Backup format $version is newer than ${BackupFormat.VERSION}")

/** A library whose backup would be larger than a restore reads, so that it is not written: it could not be restored. */
class BackupTooLargeException(val mostEntries: Int, val mostUnpacked: Long) :
    IOException("A backup holds at most $mostEntries entries and $mostUnpacked bytes unpacked")

/** The photos of the backup cannot be found without access to the library and the phone's photos. */
class NoLibraryAccessException : BackupException("No access to the library")

/**
 * A photo or video as a backup keeps it. [path] is where it is, relative to the root of its [origin] (see
 * MediaPaths); [id] is its id on the phone the backup was made on, which albums and versions refer to.
 */
internal data class MediaRecord(
    val id: Long,
    val origin: MediaOrigin,
    val path: String?,
    val name: String,
    val kind: MediaKind,
    val size: Long,
    val capturedAt: Long,
    val importedAt: Long,
    val rating: Int = 0,
    val flag: Int = 0,
    val edit: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val geoSource: GeoSource = GeoSource.NONE,
    val sourceKey: String? = null,
) {
    fun toJson() = JsonObject().apply {
        addProperty("id", id)
        addProperty("origin", origin.name)
        path?.let { addProperty("path", it) }
        addProperty("name", name)
        addProperty("kind", kind.name)
        addProperty("size", size)
        addProperty("capturedAt", capturedAt)
        addProperty("importedAt", importedAt)
        if (rating != 0) addProperty("rating", rating)
        if (flag != 0) addProperty("flag", flag)
        edit?.let { addProperty("edit", it) }
        latitude?.let { addProperty("latitude", it) }
        longitude?.let { addProperty("longitude", it) }
        altitude?.let { addProperty("altitude", it) }
        if (geoSource != GeoSource.NONE || latitude != null) addProperty("geoSource", geoSource.name)
        sourceKey?.let { addProperty("sourceKey", it) }
    }

    companion object {
        fun of(item: MediaItemEntity, path: String?) = MediaRecord(
            id = item.id,
            origin = item.origin,
            path = path,
            name = item.fileName,
            kind = item.kind,
            size = item.sizeBytes,
            capturedAt = item.capturedAt,
            importedAt = item.importedAt,
            rating = item.rating,
            flag = item.flag,
            edit = item.edit,
            latitude = item.latitude,
            longitude = item.longitude,
            altitude = item.altitude,
            geoSource = item.geoSource,
            sourceKey = item.sourceKey,
        )

        /** Null for a kind or origin that this version of the app does not know: such a photo is not restored. */
        fun fromJson(json: JsonObject): MediaRecord? {
            val origin = enumOrNull<MediaOrigin>(json.string("origin")) ?: return null
            val kind = enumOrNull<MediaKind>(json.string("kind")) ?: return null
            val latitude = json.optDouble("latitude")
            val longitude = json.optDouble("longitude")
            // A place off the Earth is no place: the phone's own, read from the file, stays.
            val located = latitude != null && longitude != null && isPlace(latitude, longitude)
            return MediaRecord(
                id = json.long("id"),
                origin = origin,
                path = json.optString("path"),
                name = json.string("name"),
                kind = kind,
                size = json.long("size"),
                capturedAt = json.long("capturedAt"),
                importedAt = json.optLong("importedAt") ?: 0,
                rating = (json.optLong("rating") ?: 0).toInt().coerceIn(0, 5),
                flag = (json.optLong("flag") ?: 0).toInt().coerceIn(-1, 1),
                edit = json.optString("edit"),
                latitude = latitude.takeIf { located },
                longitude = longitude.takeIf { located },
                altitude = json.optDouble("altitude")?.takeIf { located && it.isFinite() },
                // A place the reader cannot read is no place.
                geoSource = if (located) enumOrNull<GeoSource>(json.optString("geoSource")) ?: GeoSource.CAMERA else GeoSource.NONE,
                sourceKey = json.optString("sourceKey"),
            )
        }
    }
}

internal data class AlbumRecord(val id: Long, val name: String, val createdAt: Long) {
    companion object {
        fun of(album: AlbumEntity) = JsonObject().apply {
            addProperty("id", album.id)
            addProperty("name", album.name)
            addProperty("createdAt", album.createdAt)
        }

        fun fromJson(json: JsonObject) = AlbumRecord(json.long("id"), json.string("name"), json.optLong("createdAt") ?: 0)
    }
}

internal data class AlbumItemRecord(val album: Long, val media: Long, val addedAt: Long) {
    companion object {
        fun of(item: AlbumItemEntity) = JsonObject().apply {
            addProperty("album", item.albumId)
            addProperty("media", item.mediaId)
            addProperty("addedAt", item.addedAt)
        }

        fun fromJson(json: JsonObject) = AlbumItemRecord(json.long("album"), json.long("media"), json.optLong("addedAt") ?: 0)
    }
}

internal data class VersionRecord(val media: Long, val name: String, val edit: String, val createdAt: Long) {
    companion object {
        fun of(version: EditVersionEntity) = JsonObject().apply {
            addProperty("media", version.mediaId)
            addProperty("name", version.name)
            addProperty("edit", version.edit)
            addProperty("createdAt", version.createdAt)
        }

        fun fromJson(json: JsonObject) = VersionRecord(json.long("media"), json.string("name"), json.string("edit"), json.optLong("createdAt") ?: 0)
    }
}

internal object FilterRecord {
    fun of(filter: FilterEntity) = JsonObject().apply {
        addProperty("name", filter.name)
        addProperty("look", filter.look)
        addProperty("createdAt", filter.createdAt)
    }

    fun fromJson(json: JsonObject) = FilterEntity(name = json.string("name"), look = json.string("look"), createdAt = json.optLong("createdAt") ?: 0)
}

internal object LutRecord {
    private val ID = Regex("[0-9a-f]{32}")

    fun of(lut: LutEntity) = JsonObject().apply {
        addProperty("hash", lut.hash)
        addProperty("name", lut.name)
        addProperty("size", lut.size)
        addProperty("createdAt", lut.createdAt)
    }

    fun fromJson(json: JsonObject): LutEntity {
        val hash = json.string("hash")
        if (!ID.matches(hash)) throw IllegalArgumentException("Not a LUT id: $hash")
        return LutEntity(name = json.string("name"), size = json.long("size").toInt(), hash = hash, createdAt = json.optLong("createdAt") ?: 0)
    }
}

internal object SyncRecordRecord {
    fun of(record: SyncRecordEntity) = JsonObject().apply {
        addProperty("sourceKey", record.sourceKey)
        addProperty("importedAt", record.importedAt)
    }

    fun fromJson(json: JsonObject) = SyncRecordEntity(json.string("sourceKey"), json.optLong("importedAt") ?: 0)
}

internal object SyncedFolderRecord {
    fun of(folder: SyncedFolderEntity) = JsonObject().apply {
        addProperty("cameraId", folder.cameraId)
        addProperty("path", folder.path)
        addProperty("fingerprint", folder.fingerprint)
        addProperty("kinds", folder.kinds)
    }

    fun fromJson(json: JsonObject) = SyncedFolderEntity(json.string("cameraId"), json.string("path"), json.string("fingerprint"), json.string("kinds"))
}

internal object LocationRecord {
    fun of(point: LocationPointEntity) = JsonObject().apply {
        addProperty("timestamp", point.timestamp)
        addProperty("latitude", point.latitude)
        addProperty("longitude", point.longitude)
        point.altitude?.let { addProperty("altitude", it) }
        point.accuracy?.let { addProperty("accuracy", it) }
        addProperty("source", point.source.name)
    }

    /** Null for a source this version does not know, and for a place off the Earth, which nothing records. */
    fun fromJson(json: JsonObject): LocationPointEntity? {
        val source = enumOrNull<LocationSource>(json.string("source")) ?: return null
        val latitude = json.double("latitude")
        val longitude = json.double("longitude")
        if (!isPlace(latitude, longitude)) return null
        return LocationPointEntity(
            timestamp = json.long("timestamp"),
            latitude = latitude,
            longitude = longitude,
            // What is no number is left out, as a missing one: NaN or infinity, as only something else writes.
            altitude = json.optDouble("altitude")?.takeIf { it.isFinite() },
            accuracy = json.optDouble("accuracy")?.toFloat()?.takeIf { it.isFinite() },
            source = source,
        )
    }
}

/**
 * The preferences a backup carries: none that says where the person is or lets the app reach the internet.
 * Null is a preference the backup does not mention, which a restore leaves as it is.
 */
internal data class BackupSettings(
    val theme: ThemeMode? = null,
    val gridSize: GridSize? = null,
    val tabPosition: TabPosition? = null,
    val trashKeptDays: Int? = null,
    /** Present and null: the camera keeps the phone's zone. */
    val cameraZoneId: String? = null,
    val cameraZoneGiven: Boolean = false,
    val cameraClockOffsetMinutes: Int? = null,
    val jpegQuality: Int? = null,
    val removeLocationWhenSharing: Boolean? = null,
    val allExif: Boolean? = null,
    val phoneFolders: Set<String> = emptySet(),
    val syncPhotos: Boolean? = null,
    val syncRaw: Boolean? = null,
    val syncVideos: Boolean? = null,
    val geotagMaxGapMinutes: Int? = null,
    /** Only told, never applied: the app asks before it uses the internet. */
    val onlineMaps: Boolean = false,
) {
    /** [current] with these preferences; the phone folders are added to the ones chosen, and online maps stay as they are. */
    fun applyTo(current: AppSettings) = current.copy(
        theme = theme ?: current.theme,
        gridSize = gridSize ?: current.gridSize,
        tabPosition = tabPosition ?: current.tabPosition,
        trashKeptDays = trashKeptDays ?: current.trashKeptDays,
        cameraZoneId = if (cameraZoneGiven) cameraZoneId else current.cameraZoneId,
        cameraClockOffsetMinutes = cameraClockOffsetMinutes ?: current.cameraClockOffsetMinutes,
        jpegQuality = jpegQuality ?: current.jpegQuality,
        removeLocationWhenSharing = removeLocationWhenSharing ?: current.removeLocationWhenSharing,
        allExif = allExif ?: current.allExif,
        phoneFolders = current.phoneFolders + phoneFolders,
        syncPhotos = syncPhotos ?: current.syncPhotos,
        syncRaw = syncRaw ?: current.syncRaw,
        syncVideos = syncVideos ?: current.syncVideos,
        geotagMaxGapMinutes = geotagMaxGapMinutes ?: current.geotagMaxGapMinutes,
        onlineMaps = current.onlineMaps,
    )

    companion object {
        fun toJson(settings: AppSettings) = JsonObject().apply {
            addProperty("theme", settings.theme.name)
            addProperty("gridSize", settings.gridSize.name)
            addProperty("tabPosition", settings.tabPosition.name)
            addProperty("trashKeptDays", settings.trashKeptDays)
            add("cameraZoneId", settings.cameraZoneId?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
            addProperty("cameraClockOffsetMinutes", settings.cameraClockOffsetMinutes)
            addProperty("jpegQuality", settings.jpegQuality)
            addProperty("removeLocationWhenSharing", settings.removeLocationWhenSharing)
            addProperty("allExif", settings.allExif)
            add("phoneFolders", JsonArray().apply { settings.phoneFolders.sorted().forEach(::add) })
            addProperty("syncPhotos", settings.syncPhotos)
            addProperty("syncRaw", settings.syncRaw)
            addProperty("syncVideos", settings.syncVideos)
            addProperty("geotagMaxGapMinutes", settings.geotagMaxGapMinutes)
            addProperty("onlineMaps", settings.onlineMaps)
        }

        /** What a value the reader cannot use says is left as it is on the phone. */
        fun fromJson(json: JsonObject): BackupSettings {
            fun bool(name: String) = json.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            fun int(name: String) = json.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            val zone = json.get("cameraZoneId")
            return BackupSettings(
                theme = enumOrNull<ThemeMode>(json.optString("theme")),
                gridSize = enumOrNull<GridSize>(json.optString("gridSize")),
                tabPosition = enumOrNull<TabPosition>(json.optString("tabPosition")),
                trashKeptDays = int("trashKeptDays")?.takeIf { it in 1..3650 },
                cameraZoneId = zone?.takeIf { it.isJsonPrimitive }?.asString,
                cameraZoneGiven = zone != null && (zone.isJsonNull || zone.isJsonPrimitive),
                cameraClockOffsetMinutes = int("cameraClockOffsetMinutes"),
                jpegQuality = int("jpegQuality")?.takeIf { it in 1..100 },
                removeLocationWhenSharing = bool("removeLocationWhenSharing"),
                allExif = bool("allExif"),
                phoneFolders = json.get("phoneFolders")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }?.toSet().orEmpty(),
                syncPhotos = bool("syncPhotos"),
                syncRaw = bool("syncRaw"),
                syncVideos = bool("syncVideos"),
                geotagMaxGapMinutes = int("geotagMaxGapMinutes")?.takeIf { it > 0 },
                onlineMaps = bool("onlineMaps") ?: false,
            )
        }
    }
}

internal inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? = enumValues<T>().firstOrNull { it.name == name }

/** Whether [latitude] and [longitude] are a place on the Earth, in degrees; NaN is none. */
internal fun isPlace(latitude: Double, longitude: Double): Boolean = latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun JsonObject.present(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }

internal fun JsonObject.optString(name: String): String? = present(name)?.asString

internal fun JsonObject.optLong(name: String): Long? = present(name)?.asLong

internal fun JsonObject.optDouble(name: String): Double? = present(name)?.asDouble

internal fun JsonObject.string(name: String): String = optString(name) ?: throw IllegalArgumentException("No $name")

internal fun JsonObject.long(name: String): Long = optLong(name) ?: throw IllegalArgumentException("No $name")

internal fun JsonObject.double(name: String): Double = optDouble(name) ?: throw IllegalArgumentException("No $name")
