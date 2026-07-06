package com.plusorminustwo.postmark.service.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [planVideoTranscode] — the pure function that computes a target
 * bitrate/resolution for one Media3 Transformer pass from a video's duration,
 * whether it has an audio track, and the byte budget it must fit in.
 *
 * The actual `Transformer` call can't run in a JVM unit test (no video encoder
 * available outside a device — same reasoning as why `compressImage`'s
 * `BitmapFactory` calls have no unit test), so this is the one part of video
 * compression that IS unit-tested: the analytical decision of what to ask the
 * encoder for.
 */
class VideoTranscodePlanTest {

    @Test fun `unknown duration fails cleanly`() {
        assertNull(planVideoTranscode(durationMs = 0, hasAudio = true, budgetBytes = 500_000))
        assertNull(planVideoTranscode(durationMs = -1, hasAudio = true, budgetBytes = 500_000))
    }

    @Test fun `non-positive budget fails cleanly`() {
        assertNull(planVideoTranscode(durationMs = 10_000, hasAudio = true, budgetBytes = 0))
        assertNull(planVideoTranscode(durationMs = 10_000, hasAudio = true, budgetBytes = -100))
    }

    @Test fun `short clip with generous budget gets the top resolution tier`() {
        // 1 second clip, 900 KB budget → very high effective bitrate.
        val plan = planVideoTranscode(durationMs = 1_000, hasAudio = true, budgetBytes = 900_000)!!
        assertEquals(1080, plan.targetHeight)
        assertTrue(plan.targetVideoBitrate > 0)
    }

    @Test fun `long clip with a typical MMS budget fails rather than producing a slideshow`() {
        // 10 minutes of video squeezed into 900 KB is far below any watchable bitrate.
        assertNull(planVideoTranscode(durationMs = 600_000, hasAudio = true, budgetBytes = 900_000))
    }

    @Test fun `resolution tier drops as the computed bitrate drops`() {
        val durationMs = 30_000L
        val high = planVideoTranscode(durationMs, hasAudio = false, budgetBytes = 10_000_000)!!
        val mid  = planVideoTranscode(durationMs, hasAudio = false, budgetBytes = 3_000_000)!!
        val low  = planVideoTranscode(durationMs, hasAudio = false, budgetBytes = 1_200_000)!!
        assertTrue("expected $high.targetHeight > $mid.targetHeight", high.targetHeight >= mid.targetHeight)
        assertTrue("expected $mid.targetHeight > $low.targetHeight", mid.targetHeight >= low.targetHeight)
    }

    @Test fun `audio track reserves bitrate away from video`() {
        val withAudio = planVideoTranscode(durationMs = 20_000, hasAudio = true, budgetBytes = 2_000_000)!!
        val withoutAudio = planVideoTranscode(durationMs = 20_000, hasAudio = false, budgetBytes = 2_000_000)!!
        assertTrue(withoutAudio.targetVideoBitrate > withAudio.targetVideoBitrate)
        assertEquals(0, withoutAudio.targetAudioBitrate)
        assertTrue(withAudio.targetAudioBitrate > 0)
    }

    @Test fun `budget too small for even the floor bitrate returns null`() {
        // 60 seconds into 10 KB is nowhere near a viable bitrate.
        assertNull(planVideoTranscode(durationMs = 60_000, hasAudio = false, budgetBytes = 10_000))
    }

    @Test fun `plan never requests a negative or zero video bitrate`() {
        val durations = listOf(1_000L, 5_000L, 30_000L, 120_000L, 600_000L)
        val budgets = listOf(50_000, 200_000, 900_000, 2_000_000, 10_000_000)
        for (d in durations) for (b in budgets) {
            val plan = planVideoTranscode(d, hasAudio = true, budgetBytes = b) ?: continue
            assertTrue("targetVideoBitrate=${plan.targetVideoBitrate} for duration=$d budget=$b", plan.targetVideoBitrate > 0)
            assertTrue(plan.targetHeight in listOf(360, 480, 720, 1080))
        }
    }
}
