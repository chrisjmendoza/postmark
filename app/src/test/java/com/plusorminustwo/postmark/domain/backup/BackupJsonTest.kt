package com.plusorminustwo.postmark.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupJsonTest {

    // ---- encoding ----

    @Test
    fun `encodes flat object compactly`() {
        val json = encodeJson(linkedMapOf("a" to 1L, "b" to "x", "c" to true, "d" to null))
        assertEquals("""{"a":1,"b":"x","c":true,"d":null}""", json)
    }

    @Test
    fun `escapes newlines carriage returns and tabs`() {
        // The whole reason this codec exists: bodies with newlines must not break
        // line-delimited record framing.
        assertEquals(""""line1\nline2\r\ttabbed"""", encodeJson("line1\nline2\r\ttabbed"))
    }

    @Test
    fun `escapes quotes and backslashes`() {
        assertEquals(""""say \"hi\" C:\\dir"""", encodeJson("""say "hi" C:\dir"""))
    }

    @Test
    fun `escapes control characters as unicode`() {
        val bs = 92.toChar()
        val q = 34.toChar()
        val input = 1.toChar().toString() + "x" + 31.toChar()
        assertEquals(q.toString() + bs + "u0001x" + bs + "u001f" + q, encodeJson(input))
    }

    @Test
    fun `emoji pass through unescaped`() {
        assertEquals("\"👍❤️\"", encodeJson("👍❤️"))
    }

    // ---- parsing ----

    @Test
    fun `parses nested structures`() {
        val parsed = parseJson("""{"list":[1,{"k":"v"},[true,null]],"n":-42}""")
        val map = parsed as Map<*, *>
        val list = map["list"] as List<*>
        assertEquals(1L, list[0])
        assertEquals("v", (list[1] as Map<*, *>)["k"])
        assertEquals(listOf(true, null), list[2])
        assertEquals(-42L, map["n"])
    }

    @Test
    fun `parses integral as Long and fractional as Double`() {
        val map = parseJson("""{"i":10000000042,"d":1.5,"e":2e3}""") as Map<*, *>
        assertEquals(10_000_000_042L, map["i"])
        assertEquals(1.5, map["d"])
        assertEquals(2000.0, map["e"])
    }

    @Test
    fun `parses unicode escapes including surrogate pairs`() {
        val backslash = '\\'
        // "A" -> "A"
        assertEquals("A", parseJson("\"${backslash}u0041\""))
        // Surrogate pair "👍" -> 👍
        assertEquals("👍", parseJson("\"${backslash}ud83d${backslash}udc4d\""))
    }

    @Test
    fun `tolerates whitespace between tokens`() {
        val map = parseJson(" {\n\t\"a\" :  [ 1 , 2 ] ,\r\n \"b\" : \"x\" } ") as Map<*, *>
        assertEquals(listOf(1L, 2L), map["a"])
        assertEquals("x", map["b"])
    }

    @Test
    fun `parses empty object and array`() {
        assertEquals(emptyMap<String, Any?>(), parseJson("{}"))
        assertEquals(emptyList<Any?>(), parseJson("[]"))
        assertNull(parseJson("null"))
    }

    // ---- round trips ----

    @Test
    fun `round trips every value type`() {
        val original = linkedMapOf(
            "body" to "multi\nline \"quoted\" \\slash👍",
            "count" to 159_000L,
            "flag" to false,
            "nothing" to null,
            "nested" to listOf(linkedMapOf("x" to 1L))
        )
        assertEquals(original, parseJson(encodeJson(original)))
    }

    @Test
    fun `encoded output never contains a raw newline`() {
        val body = "a\nb\rc" + 12.toChar() + "d"
        val json = encodeJson(mapOf("b" to body))
        assertTrue(json.none { it == '\n' || it == '\r' })
        // And it round-trips back to the original control characters.
        assertEquals(mapOf("b" to body), parseJson(json))
    }

    // ---- malformed input ----

    @Test
    fun `malformed input throws`() {
        val bad = listOf(
            "", "{", """{"a":}""", """{"a" 1}""", """[1,]x""", "tru",
            """{"unterminated":"str""", """{"a":1}garbage""", "{1:2}"
        )
        for (input in bad) {
            try {
                parseJson(input)
                fail("Expected parse failure for: $input")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }
}
