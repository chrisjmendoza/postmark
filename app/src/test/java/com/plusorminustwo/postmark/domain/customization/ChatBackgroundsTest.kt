package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ChatBackgroundsTest {

    // ── Catalog shape ────────────────────────────────────────────────────────────

    @Test
    fun `catalog has None plus 6 curated backgrounds`() {
        assertEquals(7, ChatBackgrounds.all.size)
    }

    @Test
    fun `catalog includes None`() {
        assertTrue(ChatBackgrounds.all.contains(ChatBackgrounds.None))
    }

    @Test
    fun `ids are unique`() {
        val ids = ChatBackgrounds.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `display names are unique`() {
        val names = ChatBackgrounds.all.map { it.displayName }
        assertEquals(names.size, names.distinct().size)
    }

    // ── resolve() ─────────────────────────────────────────────────────────────────

    @Test
    fun `resolve round-trips every catalog entry by id`() {
        for (background in ChatBackgrounds.all) {
            assertEquals(background, ChatBackgrounds.resolve(background.id))
        }
    }

    @Test
    fun `resolve of null falls back to None`() {
        assertEquals(ChatBackgrounds.None, ChatBackgrounds.resolve(null))
    }

    @Test
    fun `resolve of an unknown id falls back to None`() {
        assertEquals(ChatBackgrounds.None, ChatBackgrounds.resolve("garbage"))
    }

    // ── Stop-list shape ──────────────────────────────────────────────────────────

    @Test
    fun `None has empty color lists`() {
        assertTrue(ChatBackgrounds.None.darkColorsArgb.isEmpty())
        assertTrue(ChatBackgrounds.None.lightColorsArgb.isEmpty())
    }

    @Test
    fun `every non-None background has equal-length non-empty dark and light stop lists`() {
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            assertTrue("${background.id}: dark stops empty", background.darkColorsArgb.isNotEmpty())
            assertTrue("${background.id}: light stops empty", background.lightColorsArgb.isNotEmpty())
            assertEquals(
                "${background.id}: dark/light stop count mismatch",
                background.darkColorsArgb.size,
                background.lightColorsArgb.size
            )
        }
    }

    // ── Legibility guard: stops land in a clearly-visible luminance band ───────────
    // Reuses ContactPalette.relativeLuminance (Phase B) rather than duplicating the
    // WCAG math here. Phase FB widened + shifted these bands after on-device testing
    // (S24 Ultra) showed v1's <2%/>79% bands were "barely noticeable" against the
    // theme background — the new bands target a visible, obviously-colored gradient.

    @Test
    fun `every dark-variant stop stays in the visible dark luminance band`() {
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            for (argb in background.darkColorsArgb) {
                val luminance = ContactPalette.relativeLuminance(argb)
                assertTrue(
                    "${background.id}: dark stop luminance $luminance not in [0.02, 0.12]",
                    luminance in 0.02..0.12
                )
            }
        }
    }

    @Test
    fun `every light-variant stop stays in the visible light luminance band`() {
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            for (argb in background.lightColorsArgb) {
                val luminance = ContactPalette.relativeLuminance(argb)
                assertTrue(
                    "${background.id}: light stop luminance $luminance not in [0.5, 0.9]",
                    luminance in 0.5..0.9
                )
            }
        }
    }

    // ── Gradient guard: the two stops must actually read as a gradient ─────────────

    @Test
    fun `dark-variant stops differ by at least 0point015 luminance`() {
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            val luminances = background.darkColorsArgb.map { ContactPalette.relativeLuminance(it) }
            val delta = luminances.max() - luminances.min()
            assertTrue(
                "${background.id}: dark stop luminance delta $delta below 0.015",
                delta >= 0.015
            )
        }
    }

    @Test
    fun `light-variant stops differ by at least 0point015 luminance`() {
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            val luminances = background.lightColorsArgb.map { ContactPalette.relativeLuminance(it) }
            val delta = luminances.max() - luminances.min()
            assertTrue(
                "${background.id}: light stop luminance delta $delta below 0.015",
                delta >= 0.015
            )
        }
    }

    // ── Global preference id normalization ──────────────────────────────────────
    // The global chat-background preference has no "Default"/follow-global concept,
    // so None.id collapses to null (its own "unset") on the way in, and null expands
    // back to None.id on the way out — see toGlobalPreferenceId/fromGlobalPreferenceId.

    @Test
    fun `toGlobalPreferenceId collapses None's id to null`() {
        assertEquals(null, ChatBackgrounds.toGlobalPreferenceId(ChatBackgrounds.None.id))
    }

    @Test
    fun `toGlobalPreferenceId passes other ids and null through unchanged`() {
        assertEquals(null, ChatBackgrounds.toGlobalPreferenceId(null))
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            assertEquals(background.id, ChatBackgrounds.toGlobalPreferenceId(background.id))
        }
    }

    @Test
    fun `fromGlobalPreferenceId expands null to None's id`() {
        assertEquals(ChatBackgrounds.None.id, ChatBackgrounds.fromGlobalPreferenceId(null))
    }

    @Test
    fun `toGlobalPreferenceId and fromGlobalPreferenceId round-trip every catalog id`() {
        for (background in ChatBackgrounds.all) {
            val stored = ChatBackgrounds.toGlobalPreferenceId(background.id)
            assertEquals(background.id, ChatBackgrounds.fromGlobalPreferenceId(stored))
        }
    }

    // ── Custom image id codec (Phase J) ──────────────────────────────────────

    @Test
    fun `makeImageId and imageFileName round-trip a file name`() {
        val id = ChatBackgrounds.makeImageId("bg_123.jpg")
        assertEquals("image:bg_123.jpg", id)
        assertTrue(ChatBackgrounds.isImageId(id))
        assertEquals("bg_123.jpg", ChatBackgrounds.imageFileName(id))
    }

    @Test
    fun `isImageId is false for null, catalog ids, and non-image strings`() {
        assertFalse(ChatBackgrounds.isImageId(null))
        assertFalse(ChatBackgrounds.isImageId("none"))
        assertFalse(ChatBackgrounds.isImageId("deep_navy"))
        assertFalse(ChatBackgrounds.isImageId("bg_1.jpg"))
    }

    @Test
    fun `imageFileName rejects malformed and path-traversal ids`() {
        // Not an image id at all.
        assertNull(ChatBackgrounds.imageFileName("deep_navy"))
        // Empty file name.
        assertNull(ChatBackgrounds.imageFileName("image:"))
        // Path separators / traversal.
        assertNull(ChatBackgrounds.imageFileName("image:../secret"))
        assertNull(ChatBackgrounds.imageFileName("image:sub/dir.jpg"))
        assertNull(ChatBackgrounds.imageFileName("image:a\\b.jpg"))
        assertNull(ChatBackgrounds.imageFileName("image:.."))
        assertNull(ChatBackgrounds.imageFileName("image:."))
    }

    @Test
    fun `resolve of an image id falls back to None (image ids are not catalog entries)`() {
        val id = ChatBackgrounds.makeImageId("bg_123.jpg")
        assertEquals(ChatBackgrounds.None, ChatBackgrounds.resolve(id))
    }

    // ── resolveImageFile ──────────────────────────────────────────────────────

    @Test
    fun `resolveImageFile returns null for null and non-image ids without calling fileFor`() {
        val fileFor: (String) -> File? = { fail("fileFor should not be called for $it"); null }
        assertNull(ChatBackgrounds.resolveImageFile(null, fileFor))
        assertNull(ChatBackgrounds.resolveImageFile("deep_navy", fileFor))
        assertNull(ChatBackgrounds.resolveImageFile("none", fileFor))
    }

    @Test
    fun `resolveImageFile delegates an image id to fileFor`() {
        val id = ChatBackgrounds.makeImageId("bg_1.jpg")
        val file = File("bg_1.jpg")
        val result = ChatBackgrounds.resolveImageFile(id) { requested ->
            assertEquals(id, requested)
            file
        }
        assertSame(file, result)
    }

    @Test
    fun `resolveImageFile returns null when fileFor reports the file missing`() {
        val id = ChatBackgrounds.makeImageId("bg_missing.jpg")
        assertNull(ChatBackgrounds.resolveImageFile(id) { null })
    }
}
