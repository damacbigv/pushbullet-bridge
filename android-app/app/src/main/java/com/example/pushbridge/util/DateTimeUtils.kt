package com.example.pushbridge.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Context-Aware Timestamp Formatter for PushBridge SMS Thread Dashboards:
 * 1. Messages sent or received on current calendar date (today): Displays only the time (e.g. "3:42 PM" or "15:42").
 * 2. Messages older than current calendar date: Displays day of week combined with month and year (e.g. "Wed, Nov 2026").
 * 3. Interactive Hover / Tap Tooltip: Full precision timestamp (exact date, hour, minute, and second).
 */
object DateTimeUtils {

    data class FormattedThreadTimestamp(
        val display: String,
        val fullTooltip: String
    )

    fun formatThreadTimestamp(timestamp: Long): FormattedThreadTimestamp {
        if (timestamp <= 0) return FormattedThreadTimestamp(display = "", fullTooltip = "")

        val date = Date(timestamp)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }

        val isToday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                      now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

        val display = if (isToday) {
            // Today: display only the time based on user locale
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        } else {
            // Older than current date: day of the week combined with month and year
            SimpleDateFormat("EEE, MMM yyyy", Locale.getDefault()).format(date)
        }

        // Full precision timestamp for interactive hover / tap tooltips
        val fullTooltip = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' hh:mm:ss a", Locale.getDefault()).format(date)

        return FormattedThreadTimestamp(display = display, fullTooltip = fullTooltip)
    }
}
