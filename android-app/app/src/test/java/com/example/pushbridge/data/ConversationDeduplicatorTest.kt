package com.example.pushbridge.data

import com.example.pushbridge.model.SmsConversationThread
import com.example.pushbridge.model.SmsMessageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDeduplicatorTest {

    @Test
    fun testNatashaThreadsMergedIntoSingleP2pThread() {
        // Simulates the exact 3 duplicate threads from the live Firebase database:
        // TID=5 (SMS), TID=656 (MMS), TID=1236 (MMS with volliver@gmail.com self email)
        val thread5 = SmsConversationThread(
            threadId = 5L,
            address = "+15052056397",
            contactName = "Natasha Olliver",
            snippet = "I am parked in front of Jimmy John’s",
            lastTimestamp = 1794762155051L,
            isGroup = false,
            recipients = listOf(mapOf("phone" to "+15052056397", "name" to "Natasha Olliver", "cleanDigits" to "15052056397")),
            messages = mapOf(
                "sms_1" to SmsMessageItem(id = "sms_1", threadId = 5L, address = "+15052056397", body = "I am parked in front of Jimmy John’s", timestamp = 1794762155051L, isIncoming = true).toMap()
            )
        )

        val thread656 = SmsConversationThread(
            threadId = 656L,
            address = "+15052056397",
            contactName = "Natasha Olliver",
            snippet = "I am parked in front of Jimmy John’s",
            lastTimestamp = 1794762155051L,
            isGroup = false,
            recipients = listOf(mapOf("phone" to "+15052056397", "name" to "Natasha Olliver", "cleanDigits" to "15052056397")),
            messages = mapOf(
                "mms_1" to SmsMessageItem(id = "mms_1", threadId = 656L, address = "+15052056397", body = "I am parked in front of Jimmy John’s", timestamp = 1794762155051L, isIncoming = true).toMap()
            )
        )

        val thread1236 = SmsConversationThread(
            threadId = 1236L,
            address = "+15052056397, volliver@gmail.com",
            contactName = "Natasha Olliver, volliver@gmail.com",
            groupTitle = "Natasha Olliver, volliver@gmail.com",
            snippet = "Headed home",
            lastTimestamp = 1794870875180L,
            isGroup = true, // Erroneously flagged group due to volliver@gmail.com
            recipients = listOf(
                mapOf("phone" to "+15052056397", "name" to "Natasha Olliver", "cleanDigits" to "15052056397"),
                mapOf("phone" to "volliver@gmail.com", "name" to "volliver@gmail.com", "cleanDigits" to "")
            ),
            messages = mapOf(
                "rcs_2" to SmsMessageItem(id = "rcs_2", threadId = 1236L, address = "+15052056397", body = "Headed home", timestamp = 1794870875180L, isIncoming = true, isRcs = true).toMap()
            )
        )

        val merged = ConversationDeduplicator.deduplicateAndMergeThreads(
            listOf(thread5, thread656, thread1236),
            selfPhoneDigits = "17578717566"
        )

        assertEquals("Should merge 3 duplicate threads into 1", 1, merged.size)
        val unified = merged.first()
        assertEquals("Should preserve lowest stable telephony thread ID", 5L, unified.threadId)
        assertEquals("Should normalize contact display name", "Natasha Olliver", unified.contactName)
        assertFalse("Should not be classified as group after stripping self email", unified.isGroup)
        assertEquals("Should preserve latest snippet", "Headed home", unified.snippet)
        assertEquals("Should preserve latest timestamp", 1794870875180L, unified.lastTimestamp)
        assertEquals("Should merge messages with deduplication", 2, unified.messages.size)
    }

    @Test
    fun testFamilyThreadsMergedIntoNamedGroup() {
        // Simulates the family threads:
        // TID=494 (Mom + Natasha, title "Parent's and Wife")
        // TID=64 (Dad, title "Parent's and Wife")
        // TID=1777 (carrier token +444332222235, Dad, Mom, Natasha, Self +17578717566)
        val thread494 = SmsConversationThread(
            threadId = 494L,
            address = "+15052056397, +17578105408",
            contactName = "Parent's and Wife",
            groupTitle = "Parent's and Wife",
            snippet = "Wow, that was fast! Happy to know you all are home safely!",
            lastTimestamp = 1794710979000L,
            isGroup = true,
            recipients = listOf(
                mapOf("phone" to "+15052056397", "name" to "Natasha Olliver", "cleanDigits" to "15052056397"),
                mapOf("phone" to "+17578105408", "name" to "Mom Bertha Olliver", "cleanDigits" to "17578105408")
            )
        )

        val thread64 = SmsConversationThread(
            threadId = 64L,
            address = "+17578807247",
            contactName = "Parent's and Wife",
            groupTitle = "Parent's and Wife",
            snippet = "Happy Veterans Day to you, my son",
            lastTimestamp = 1794441945000L,
            isGroup = true,
            recipients = listOf(
                mapOf("phone" to "+17578807247", "name" to "Dad Valla Olliver II", "cleanDigits" to "17578807247")
            )
        )

        val thread1777 = SmsConversationThread(
            threadId = 1777L,
            address = "+444332222235, +17578807247, +17578105408, +15052056397, +17578717566",
            contactName = "+444332222235, Dad Valla Olliver II, Mom Bertha Olliver, Natasha Olliver, Valla Vonga Olliver, III",
            groupTitle = "+444332222235, Dad Valla Olliver II, Mom Bertha Olliver, Natasha Olliver, Valla Vonga Olliver, III",
            snippet = "❤️ to “Wow, that was fast! Happy to know you all are home safely!”",
            lastTimestamp = 1794711004000L,
            isGroup = true,
            recipients = listOf(
                mapOf("phone" to "+444332222235", "name" to "+444332222235", "cleanDigits" to "444332222235"),
                mapOf("phone" to "+17578807247", "name" to "Dad Valla Olliver II", "cleanDigits" to "17578807247"),
                mapOf("phone" to "+17578105408", "name" to "Mom Bertha Olliver", "cleanDigits" to "17578105408"),
                mapOf("phone" to "+15052056397", "name" to "Natasha Olliver", "cleanDigits" to "15052056397"),
                mapOf("phone" to "+17578717566", "name" to "Valla Vonga Olliver, III", "cleanDigits" to "17578717566")
            )
        )

        val merged = ConversationDeduplicator.deduplicateAndMergeThreads(
            listOf(thread494, thread64, thread1777),
            selfPhoneDigits = "17578717566"
        )

        assertEquals("Should merge family threads into a single conversation", 1, merged.size)
        val unified = merged.first()
        assertEquals("Should retain genuine group name", "Parent's and Wife", unified.groupTitle)
        assertTrue("Should be marked as group", unified.isGroup)
        assertEquals("Should filter carrier and self recipients", 3, unified.recipients.size)
    }

    @Test
    fun testCompoundMessageFingerprintingDeduplication() {
        val msgSms = SmsMessageItem(
            id = "sms_101",
            threadId = 5L,
            address = "+15052056397",
            contactName = "Natasha Olliver",
            body = "I am parked in front of Jimmy John’s",
            timestamp = 1794762155000L,
            isIncoming = true,
            isRcs = false
        )

        // Arrived 1.5 seconds later via RCS notification listener with zero-width whitespace
        val msgRcs = SmsMessageItem(
            id = "rcs_102",
            threadId = 8000000005L,
            address = "+15052056397",
            contactName = "Natasha Olliver",
            body = "\u200BI am parked in front of Jimmy John’s\u200B",
            timestamp = 1794762156500L,
            isIncoming = true,
            isRcs = true,
            protocol = "RCS"
        )

        val deduped = ConversationDeduplicator.deduplicateMessages(listOf(msgSms, msgRcs))
        assertEquals("Identical messages within ±5s window should merge into 1 bubble", 1, deduped.size)
        val merged = deduped.first()
        assertTrue("Should inherit richer RCS protocol flag", merged.isRcs)
        assertEquals("RCS", merged.protocol)
    }
}
