package com.plusorminustwo.postmark.domain.search

import com.plusorminustwo.postmark.domain.model.Thread
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-filter tests for [matchThreads] — no Android deps. */
class ThreadMatchingTest {

    private fun thread(
        id: Long,
        name: String,
        address: String = "+$id",
        nickname: String? = null
    ) = Thread(id = id, displayName = name, address = address, lastMessageAt = 0L, nickname = nickname)

    @Test
    fun `blank query matches nothing`() {
        val threads = listOf(thread(1, "Alice"), thread(2, "Bob"))
        assertEquals(emptyList<Thread>(), matchThreads("   ", threads))
    }

    @Test
    fun `name match is case insensitive`() {
        val threads = listOf(thread(1, "Alice"), thread(2, "Bob"))
        val ids = matchThreads("ALI", threads).map { it.id }
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `digit match ignores address formatting`() {
        // Query digits "5551234" must find "+1 (555) 123-4567" despite punctuation.
        val threads = listOf(
            thread(1, "Alice", address = "+1 (555) 123-4567"),
            thread(2, "Bob", address = "+1 (999) 000-0000")
        )
        val ids = matchThreads("555 123", threads).map { it.id }
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `name matches rank above address-only matches`() {
        // Query "55" is in Bob's name and in Alice's address only.
        val threads = listOf(
            thread(1, "Alice", address = "+15500000000"),
            thread(2, "55 Diner", address = "+19990000000")
        )
        val ids = matchThreads("55", threads).map { it.id }
        assertEquals(listOf(2L, 1L), ids)
    }

    @Test
    fun `matches within a rank are alphabetical by shown name`() {
        val threads = listOf(thread(1, "Bob"), thread(2, "Robot"), thread(3, "Mom"))
        val names = matchThreads("o", threads).map { it.displayName }
        assertEquals(listOf("Bob", "Mom", "Robot"), names)
    }

    @Test
    fun `nickname is matched and used for display`() {
        val threads = listOf(thread(1, "+15551234567", nickname = "Landlord"))
        val ids = matchThreads("land", threads).map { it.id }
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `no match yields empty list`() {
        val threads = listOf(thread(1, "Alice", address = "+111"), thread(2, "Bob", address = "+222"))
        assertEquals(emptyList<Thread>(), matchThreads("zzz", threads))
    }

    @Test
    fun `result is capped at the limit`() {
        val threads = (1L..10L).map { thread(it, "Contact $it") }
        assertEquals(CONTACT_MATCH_LIMIT, matchThreads("contact", threads).size)
    }
}
