package com.plusorminustwo.postmark.search.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsQueryBuilderTest {

    @Test
    fun `blank input returns empty string`() {
        assertEquals("", FtsQueryBuilder.build(""))
        assertEquals("", FtsQueryBuilder.build("   "))
    }

    @Test
    fun `single word produces FTS4 phrase-prefix syntax with star inside quotes`() {
        assertEquals("\"hello*\"", FtsQueryBuilder.build("hello"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("\"hello*\"", FtsQueryBuilder.build("  hello  "))
    }

    @Test
    fun `multi-word input becomes one phrase with prefix on the last word`() {
        assertEquals("\"say hi*\"", FtsQueryBuilder.build("say hi"))
    }

    @Test
    fun `internal whitespace runs collapse to single spaces`() {
        assertEquals("\"hello world*\"", FtsQueryBuilder.build("  hello   world  "))
    }

    @Test
    fun `embedded double-quotes are replaced with spaces`() {
        // FTS4 has no in-phrase quote escaping; the tokenizer drops quotes from indexed
        // tokens anyway, so 'say "hi"' and 'say hi' match the same messages.
        assertEquals("\"say hi*\"", FtsQueryBuilder.build("say \"hi\""))
    }

    @Test
    fun `quotes-only input returns empty string`() {
        assertEquals("", FtsQueryBuilder.build("\"\""))
    }
}
