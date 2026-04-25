package com.plusorminustwo.postmark.search.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsQueryBuilderTest {

    @Test
    fun `blank input returns empty string`() {
        assertEquals("", FtsQueryBuilder.build(""))
        assertEquals("", FtsQueryBuilder.build("   "))
    }

    @Test
    fun `single word produces word-start prefix syntax`() {
        assertEquals("^\"hello\"*", FtsQueryBuilder.build("hello"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("^\"hello\"*", FtsQueryBuilder.build("  hello  "))
    }

    @Test
    fun `embedded double-quotes are escaped`() {
        // A query containing a literal " must not break FTS5 MATCH syntax
        assertEquals("^\"say \"\"hi\"\"\"*", FtsQueryBuilder.build("say \"hi\""))
    }

    @Test
    fun `multi-word query produces one prefix clause per word`() {
        val result = FtsQueryBuilder.buildMultiWord("did you")
        assertEquals("^\"did\"* ^\"you\"*", result)
    }

    @Test
    fun `multi-word trims and ignores extra whitespace between words`() {
        val result = FtsQueryBuilder.buildMultiWord("  hello   world  ")
        assertEquals("^\"hello\"* ^\"world\"*", result)
    }

    @Test
    fun `multi-word blank returns empty`() {
        assertTrue(FtsQueryBuilder.buildMultiWord("   ").isEmpty())
    }

    @Test
    fun `single word via buildMultiWord equals build`() {
        assertEquals(FtsQueryBuilder.build("hi"), FtsQueryBuilder.buildMultiWord("hi"))
    }
}
