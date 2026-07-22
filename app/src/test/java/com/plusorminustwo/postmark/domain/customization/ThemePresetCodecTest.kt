package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exhaustive tests for the `.postmarktheme` codec — the seed of the shareable theme
 * "market", so its round-trip fidelity and its tolerance of hand-edited / hostile input
 * are the contract, not an implementation detail.
 */
class ThemePresetCodecTest {

    // ── Round-trip ───────────────────────────────────────────────────────────────

    @Test
    fun `every built-in preset round-trips losslessly`() {
        for (preset in ThemePresets.all) {
            val restored = ThemePresetCodec.parse(ThemePresetCodec.serialize(preset))
            assertEquals("preset ${preset.id} did not round-trip", preset, restored)
        }
    }

    @Test
    fun `serialize produces the documented v1 shape`() {
        val sunset = ThemePresets.all.first { it.id == "sunset" }
        val expected = """
            {
              "format": "postmark-theme",
              "schemaVersion": 1,
              "name": "Sunset",
              "author": "",
              "sent": "#7C3AC9",
              "contact": "#F2694B",
              "background": "deep_plum"
            }
        """.trimIndent()
        assertEquals(expected, ThemePresetCodec.serialize(sunset))
    }

    // ── Forward compatibility ────────────────────────────────────────────────────

    @Test
    fun `unknown keys are ignored`() {
        val json = """
            {
              "format": "postmark-theme",
              "schemaVersion": 1,
              "name": "Sunset",
              "author": "Chris",
              "sent": "#7C3AC9",
              "contact": "#F2694B",
              "background": "deep_plum",
              "futureField": { "nested": [1, 2, 3] },
              "anotherUnknown": 42
            }
        """.trimIndent()
        assertEquals(
            ThemePreset("sunset", "Sunset", 0xFF7C3AC9.toInt(), 0xFFF2694B.toInt(), "deep_plum"),
            ThemePresetCodec.parse(json)
        )
    }

    @Test
    fun `a newer schemaVersion is refused`() {
        val json = """{"format":"postmark-theme","schemaVersion":2,"name":"X","author":"","sent":"#111111","contact":"#222222","background":"none"}"""
        assertNull(ThemePresetCodec.parse(json))
    }

    // ── Malformed input never throws, always null ────────────────────────────────

    @Test
    fun `garbage and truncated input parse to null`() {
        val bad = listOf(
            "",
            "   ",
            "not json at all",
            "{",
            "{}",
            "[1, 2, 3]",                                    // valid JSON, wrong root type
            "42",                                           // valid JSON, wrong root type
            """{"format":"postmark-theme","schemaVersion":1,"name":"X"""  // truncated mid-object
        )
        for (input in bad) {
            assertNull("expected null for input <$input>", ThemePresetCodec.parse(input))
        }
    }

    @Test
    fun `wrong or missing format tag parses to null`() {
        val wrong = """{"format":"something-else","schemaVersion":1,"name":"X","sent":"#111111","contact":"#222222","background":"none"}"""
        val missing = """{"schemaVersion":1,"name":"X","sent":"#111111","contact":"#222222","background":"none"}"""
        assertNull(ThemePresetCodec.parse(wrong))
        assertNull(ThemePresetCodec.parse(missing))
    }

    @Test
    fun `missing name or missing or invalid colors parse to null`() {
        val base = """"format":"postmark-theme","schemaVersion":1,"background":"none""""
        assertNull(ThemePresetCodec.parse("""{$base,"sent":"#111111","contact":"#222222"}"""))            // no name
        assertNull(ThemePresetCodec.parse("""{$base,"name":"X","contact":"#222222"}"""))                  // no sent
        assertNull(ThemePresetCodec.parse("""{$base,"name":"X","sent":"#111111"}"""))                     // no contact
        assertNull(ThemePresetCodec.parse("""{$base,"name":"X","sent":"nothex","contact":"#222222"}"""))  // bad hex
    }

    // ── Background handling ──────────────────────────────────────────────────────

    @Test
    fun `background none parses to a null backgroundId`() {
        val json = """{"format":"postmark-theme","schemaVersion":1,"name":"X","author":"","sent":"#111111","contact":"#222222","background":"none"}"""
        assertNull(ThemePresetCodec.parse(json)?.backgroundId)
    }

    @Test
    fun `an unknown background id is kept for resolve to map to None later`() {
        val json = """{"format":"postmark-theme","schemaVersion":1,"name":"X","author":"","sent":"#111111","contact":"#222222","background":"totally_unknown"}"""
        val parsed = ThemePresetCodec.parse(json)
        assertEquals("totally_unknown", parsed?.backgroundId)
        // The stale id is harmless: the catalog's resolve() contract maps it to None at render.
        assertEquals(ChatBackgrounds.None, ChatBackgrounds.resolve(parsed?.backgroundId))
    }

    // ── Escaping ─────────────────────────────────────────────────────────────────

    @Test
    fun `names with quotes backslashes and emoji survive the round-trip`() {
        val gnarly = ThemePreset(
            id = "unused",  // id is re-derived from the name on parse; the schema carries no id
            name = "Ocean \"Deep\" \\ Vibes 🌊🎨",
            sentArgb = 0xFF2456C4.toInt(),
            contactArgb = 0xFF12A5A0.toInt(),
            backgroundId = "midnight_teal"
        )
        val parsed = ThemePresetCodec.parse(ThemePresetCodec.serialize(gnarly))
        assertEquals(gnarly.name, parsed?.name)
        assertEquals(gnarly.sentArgb, parsed?.sentArgb)
        assertEquals(gnarly.contactArgb, parsed?.contactArgb)
        assertEquals(gnarly.backgroundId, parsed?.backgroundId)
    }
}
