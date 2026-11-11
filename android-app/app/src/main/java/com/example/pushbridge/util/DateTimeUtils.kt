package com.example.pushbridge.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Context-Aware Timestamp Formatter for PushBridge SMS Thread Dashboards:
 * 1. Messages sent or received on current calendar date (today): Displays only the time (e.g. "3:42 PM").
 * 2. Messages older than current calendar date: Displays day of week combined with month and year (e.g. "Wed, Nov 2026").
 */
object DateTimeUtils {

    fun formatThreadTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return ""

        val date = Date(timestamp)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }

        val isToday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

        return if (isToday) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("EEE, MMM yyyy", Locale.getDefault()).format(date)
        }
    }

    fun formatMessageTime(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
    }

    fun formatDateDivider(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val date = Date(timestamp)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }

        if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        ) {
            return "Today"
        }

        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        ) {
            return "Yesterday"
        }

        return if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR)) {
            SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
        }
    }

    fun formatRelativeNotificationTime(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val diff = System.currentTimeMillis() - timestamp
        if (diff in 0L until 60_000L) return "Just now"
        if (diff in 60_000L until 3_600_000L) return "${diff / 60_000L}m ago"
        return formatMessageTime(timestamp)
    }
}
