package com.byeonggon.latent.data

import android.icu.text.TimeZoneNames
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/** A time zone to choose from: [id] ("Asia/Seoul"), the [city] it is named after, and its [offset] now. */
data class TimeZoneChoice(val id: String, val city: String, val offset: ZoneOffset) {
    /** "UTC+09:00", "UTC", "UTC-03:30". */
    val offsetLabel: String
        get() = if (offset.totalSeconds == 0) "UTC" else "UTC" + offset.id.replace("Z", "")

    /** Whether every word of [query] is somewhere in the zone's names, upper or lower case alike. */
    fun matches(query: String, zoneName: String): Boolean {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val haystack = "$city $id $zoneName $offsetLabel".lowercase()
        return words.all { it in haystack }
    }
}

object TimeZoneChoices {
    // Names of zones kept for old software; each has a proper region name too.
    private val LEGACY_PREFIXES = listOf("Etc/", "SystemV/", "US/", "Canada/", "Brazil/", "Chile/", "Mexico/")

    /** Every region's zone, west to east, with the city named in [locale]'s language where the phone knows it. */
    fun all(locale: Locale = Locale.getDefault(), now: Instant = Instant.now()): List<TimeZoneChoice> {
        val names = TimeZoneNames.getInstance(locale)
        return ZoneId.getAvailableZoneIds()
            .filter { '/' in it && LEGACY_PREFIXES.none(it::startsWith) }
            .map { id ->
                val city = names.getExemplarLocationName(id)?.takeIf { it.isNotBlank() } ?: id.substringAfterLast('/').replace('_', ' ')
                TimeZoneChoice(id, city, ZoneId.of(id).rules.getOffset(now))
            }
            .plus(TimeZoneChoice("UTC", "UTC", ZoneOffset.UTC))
            .sortedWith(compareBy<TimeZoneChoice> { it.offset.totalSeconds }.thenBy { it.city })
    }

    /** What [choices] is narrowed to by [query]; all of them when there is none. */
    fun search(choices: List<TimeZoneChoice>, query: String, locale: Locale = Locale.getDefault()): List<TimeZoneChoice> =
        if (query.isBlank()) choices else choices.filter { it.matches(query, TimeZone.getTimeZone(it.id).getDisplayName(locale)) }

    /** The name of the zone as the person reads it: "Seoul · UTC+09:00". */
    fun describe(id: String?, choices: List<TimeZoneChoice>): String? = id?.let { chosen ->
        choices.firstOrNull { it.id == chosen }?.let { "${it.city} · ${it.offsetLabel}" } ?: chosen
    }
}
