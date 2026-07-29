package com.pennywiseai.ynab.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/**
 * Coarse "time since" for the Home header ("2 minutes ago"). Pure so it is unit-tested
 * without a clock: the caller passes both `now` and `then`. Anything under a minute is
 * "just now"; otherwise the largest whole unit (minutes < hours < days) wins.
 */
fun relativeTime(nowMillis: Long, thenMillis: Long): String {
    val delta = (nowMillis - thenMillis).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> "just now"
        hours < 1 -> "$minutes ${plural(minutes, "minute")} ago"
        days < 1 -> "$hours ${plural(hours, "hour")} ago"
        else -> "$days ${plural(days, "day")} ago"
    }
}

/** Wall-clock time of an instant in [zone], e.g. "9:12 AM" — the header's secondary line. */
fun absoluteTime(thenMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(thenMillis).atZone(zone).format(CLOCK_FORMAT)

private fun plural(n: Long, unit: String) = if (n == 1L) unit else "${unit}s"
