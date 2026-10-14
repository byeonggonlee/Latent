package com.byeonggon.latent.ui.common

import android.text.format.DateFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val formats = ConcurrentHashMap<Pair<Locale, String>, DateTimeFormatter>()

/**
 * Dates and times as the phone's language writes them, from a [skeleton] of the fields wanted, such
 * as "MMMdEEEE" for "Thursday, October 9" or "10월 9일 목요일".
 */
fun localFormat(skeleton: String): DateTimeFormatter {
    val locale = Locale.getDefault()
    return formats.getOrPut(locale to skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}
