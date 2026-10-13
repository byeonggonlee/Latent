package com.byeonggon.cameramanager.location

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Turns the capture-time strings stored in photos and videos into epoch milliseconds. */
object MediaTime {
    private val EXIF_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val VIDEO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss[.SSS]'Z'")

    // Containers without a creation time report the 1904 QuickTime epoch.
    private const val EARLIEST_PLAUSIBLE_YEAR = 1990

    /**
     * EXIF stores wall-clock time; [offset] (e.g. "+09:00", OffsetTimeOriginal) pins it to an instant.
     * Without it the camera clock is assumed to run in [fallbackZone].
     */
    fun parseExif(dateTime: String?, offset: String?, fallbackZone: ZoneId): Long? {
        if (dateTime.isNullOrBlank()) return null
        val local = try {
            LocalDateTime.parse(dateTime.trim(), EXIF_FORMAT)
        } catch (e: DateTimeParseException) {
            return null
        }
        val zone = offset?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseOffset) ?: fallbackZone
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    /** Parses the UTC date reported by MediaMetadataRetriever, e.g. "20261013T053000.000Z". */
    fun parseVideo(date: String?): Long? {
        if (date.isNullOrBlank()) return null
        val utc = try {
            LocalDateTime.parse(date.trim(), VIDEO_FORMAT)
        } catch (e: DateTimeParseException) {
            return null
        }
        if (utc.year < EARLIEST_PLAUSIBLE_YEAR) return null
        return utc.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private fun parseOffset(text: String): ZoneOffset? = try {
        ZoneOffset.of(text)
    } catch (e: DateTimeException) {
        null
    }
}
