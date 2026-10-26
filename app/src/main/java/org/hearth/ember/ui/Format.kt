package org.hearth.ember.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val zone get() = ZoneId.systemDefault()
private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val fullDate = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
private val fullDateYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())

fun clockTime(ms: Long): String = clock.format(Instant.ofEpochMilli(ms).atZone(zone))

/** The time shown beside a chat in the list: the clock today, "Yesterday", a weekday this week, else the date. */
fun listTime(ms: Long): String {
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        day == today -> clockTime(ms)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> weekday.format(day)
        day.year == today.year -> shortDate.format(day)
        else -> fullDateYear.format(day)
    }
}

/** The divider between days inside a chat. */
fun dayLabel(ms: Long): String {
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> fullDate.format(day)
        else -> fullDateYear.format(day)
    }
}

fun sameDay(a: Long, b: Long): Boolean =
    Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0)
}

fun callTimer(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}
