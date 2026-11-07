package com.example.pushbridge.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Context-Aware Timestamp Formatter for PushBridge SMS Thread Dashboards:
 * 1. Messages sent or received on current calendar date (today): Displays only the time (e.g. "3:42 PM" or "15:42").
 * 2. Messages older than current calendar date: Displays day of week combined with month and year (e.g. "Wed, Nov 2026").
 */
object DateTimeUtils {

    fun formatThreadTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return ""

        val date = Date(timestamp)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }

        val isToday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                      now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

        return if (isToday) {
            // Today: display only the time based on user locale
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        } else {
            // Older than current date: day of the week combined with month and year
            SimpleDateFormat("EEE, MMM yyyy", Locale.getDefault()).format(date)
        }
    }
}
