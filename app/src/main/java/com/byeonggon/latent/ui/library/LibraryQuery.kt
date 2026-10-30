package com.byeonggon.latent.ui.library

import androidx.annotation.StringRes
import com.byeonggon.latent.R
import com.byeonggon.latent.data.db.MediaItemEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Light to dark: the ISO ranges photos are looked for by. */
enum class IsoBand(@StringRes val label: Int, private val from: Int, private val to: Int) {
    LOW(R.string.iso_low, 0, 400),
    MID(R.string.iso_mid, 401, 1600),
    HIGH(R.string.iso_high, 1601, 6400),
    VERY_HIGH(R.string.iso_very_high, 6401, Int.MAX_VALUE),
    ;

    fun accepts(iso: Int) = iso in from..to
}

/** Wide to long: the focal lengths (35 mm terms) photos are looked for by. */
enum class FocalBand(@StringRes val label: Int, private val from: Double, private val to: Double) {
    WIDE(R.string.focal_wide, 0.0, 24.0),
    STANDARD(R.string.focal_standard, 24.0, 70.0),
    TELE(R.string.focal_tele, 70.0, Double.MAX_VALUE),
    ;

    // The ranges meet at 24 and 70; a photo at 24 mm is standard, one at 70 mm too.
    fun accepts(millimetres: Double) = when (this) {
        WIDE -> millimetres < 24.0
        STANDARD -> millimetres in 24.0..70.0
        TELE -> millimetres > 70.0
    }
}

/** How far back to look. */
enum class Period(@StringRes val label: Int, val days: Long?) {
    ANY(R.string.period_any, null),
    WEEK(R.string.period_week, 7),
    MONTH(R.string.period_month, 30),
}

/** A condition that is asked to be met, not met, or that does not matter. */
enum class Ask { ANY, YES, NO }

/**
 * What the library is searched for: every condition set has to hold at once, and within a list (cameras,
 * lenses, ISO ranges) any one will do. A photo whose file does not say, of its camera say, is not found by
 * a condition on it.
 */
data class LibraryQuery(
    /** Words that all have to be in the file's name. */
    val text: String = "",
    val cameras: Set<String> = emptySet(),
    val lenses: Set<String> = emptySet(),
    val iso: Set<IsoBand> = emptySet(),
    val focal: Set<FocalBand> = emptySet(),
    val period: Period = Period.ANY,
    /** A year of the calendar; not together with a [period]. */
    val year: Int? = null,
    /** Days of the calendar, both ends included; not together with a [period] or a [year]. */
    val dates: ClosedRange<LocalDate>? = null,
    /** Whether the photo has a place. */
    val located: Ask = Ask.ANY,
    /** Whether the photo has been edited. */
    val edited: Ask = Ask.ANY,
) {
    /** How many conditions are set. */
    val count: Int
        get() = listOf(
            text.isNotBlank(), cameras.isNotEmpty(), lenses.isNotEmpty(), iso.isNotEmpty(), focal.isNotEmpty(),
            period != Period.ANY || year != null || dates != null, located != Ask.ANY, edited != Ask.ANY,
        ).count { it }

    val isActive: Boolean get() = count > 0

    /**
     * Whether [item] is found. [isEdited] says whether a photo has been edited; it reads files, so it is only
     * asked when the query depends on it.
     */
    fun accepts(item: MediaItemEntity, now: Instant, zone: ZoneId, isEdited: (MediaItemEntity) -> Boolean): Boolean {
        if (text.isNotBlank()) {
            val name = item.fileName.lowercase()
            if (!text.trim().lowercase().split(' ').filter { it.isNotEmpty() }.all { it in name }) return false
        }
        if (cameras.isNotEmpty() && item.camera !in cameras) return false
        if (lenses.isNotEmpty() && item.lens !in lenses) return false
        if (iso.isNotEmpty()) {
            val value = item.iso ?: return false
            if (iso.none { it.accepts(value) }) return false
        }
        if (focal.isNotEmpty()) {
            val value = item.focalLength ?: return false
            if (focal.none { it.accepts(value) }) return false
        }
        period.days?.let { days -> if (item.capturedAt < now.minusSeconds(days * 86_400).toEpochMilli() || item.capturedAt > now.toEpochMilli()) return false }
        year?.let { if (Instant.ofEpochMilli(item.capturedAt).atZone(zone).year != it) return false }
        dates?.let { if (Instant.ofEpochMilli(item.capturedAt).atZone(zone).toLocalDate() !in it) return false }
        if (located != Ask.ANY && (item.latitude != null && item.longitude != null) != (located == Ask.YES)) return false
        if (edited != Ask.ANY && isEdited(item) != (edited == Ask.YES)) return false
        return true
    }
}
