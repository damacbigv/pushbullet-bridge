package com.example.pushbridge.data

import com.example.pushbridge.util.DateTimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizationAndFormattingTest {

    @Test
    fun normalizePhoneNumber_rejectsContentAndLookupUris() {
        assertEquals("", SmsRepository.normalizePhoneNumber("content://com.android.contacts/contacts/lookup/3330r370-58761234567890"))
        assertEquals("", SmsRepository.normalizePhoneNumber("333037058761234567890"))
        assertEquals("", SmsRepository.normalizePhoneNumber("insert-address-token"))
        assertEquals("", SmsRepository.normalizePhoneNumber("mailto:person@example.com"))
    }

    @Test
    fun normalizePhoneNumber_formatsStandardAndShortCodeNumbers() {
        assertEquals("+17575551234", SmsRepository.normalizePhoneNumber("(757) 555-1234"))
        assertEquals("+17575551234", SmsRepository.normalizePhoneNumber("1-757-555-1234"))
        assertEquals("+17575551234", SmsRepository.normalizePhoneNumber("tel:+17575551234"))
        assertEquals("28462", SmsRepository.normalizePhoneNumber("28462"))
    }

    @Test
    fun isValidPhoneAddress_validatesProperNumbersAndRejectsGarbage() {
        assertTrue(SmsRepository.isValidPhoneAddress("+17575551234"))
        assertTrue(SmsRepository.isValidPhoneAddress("28462"))
        assertFalse(SmsRepository.isValidPhoneAddress("content://com.android.contacts/contacts/lookup/123"))
        assertFalse(SmsRepository.isValidPhoneAddress("333037058761234567890"))
        assertFalse(SmsRepository.isValidPhoneAddress("Natasha Olliver"))
        assertFalse(SmsRepository.isValidPhoneAddress(""))
    }

    @Test
    fun normalizeConversationTitle_stripsSummaryCountsAndLocks() {
        assertEquals(
            "MD/ NC Fantasy Football 2026-2027",
            RcsConversationManager.normalizeConversationTitle("MD/ NC Fantasy Football 2026-2027 (8 messages)")
        )
        assertEquals(
            "Dad Valla Olliver II, Mom Bertha Olliver",
            RcsConversationManager.normalizeConversationTitle("🔒 Dad Valla Olliver II, Mom Bertha Olliver (1 message)")
        )
        assertNull(RcsConversationManager.normalizeConversationTitle("   "))
    }

    @Test
    fun dateTimeUtils_formatsContextAwareTimestamps() {
        val now = System.currentTimeMillis()
        val todayLabel = DateTimeUtils.formatThreadTimestamp(now)
        assertTrue(todayLabel.contains(":"))
        assertTrue(todayLabel.endsWith("AM") || todayLabel.endsWith("PM"))

        val threeDaysAgo = now - 3L * 24L * 60L * 60L * 1000L
        val olderLabel = DateTimeUtils.formatThreadTimestamp(threeDaysAgo)
        assertTrue(olderLabel.contains(","))
        assertEquals("Today", DateTimeUtils.formatDateDivider(now))
        assertEquals("Just now", DateTimeUtils.formatRelativeNotificationTime(now - 5_000L))
        assertEquals("5m ago", DateTimeUtils.formatRelativeNotificationTime(now - 5L * 60_000L))
    }
}
