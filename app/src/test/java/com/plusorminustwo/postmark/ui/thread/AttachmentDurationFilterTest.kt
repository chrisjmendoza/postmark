package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.domain.model.MessageAttachment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ThreadViewModel.partitionAttachmentsByDuration] — the decision logic
 * behind [ThreadViewModel.onAttachmentsSelected]'s 10-second video duration cap
 * ([com.plusorminustwo.postmark.service.sms.MmsManagerWrapper.MAX_VIDEO_DURATION_MS]).
 */
class AttachmentDurationFilterTest {

    private val img = MessageAttachment("content://img/1", "image/jpeg")
    private fun video(id: Int) = MessageAttachment("content://video/$id", "video/mp4")

    @Test fun `non-video attachments always pass through`() {
        val (accepted, rejected) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(img), emptyMap(), maxDurationMs = 10_000L
        )
        assertEquals(listOf(img), accepted)
        assertEquals(0, rejected)
    }

    @Test fun `video under the cap is accepted`() {
        val v = video(1)
        val (accepted, rejected) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(v), mapOf(v.uri to 9_000L), maxDurationMs = 10_000L
        )
        assertEquals(listOf(v), accepted)
        assertEquals(0, rejected)
    }

    @Test fun `video exactly at the cap is accepted`() {
        val v = video(1)
        val (accepted, rejected) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(v), mapOf(v.uri to 10_000L), maxDurationMs = 10_000L
        )
        assertEquals(listOf(v), accepted)
        assertEquals(0, rejected)
    }

    @Test fun `video over the cap is rejected`() {
        val v = video(1)
        val (accepted, rejected) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(v), mapOf(v.uri to 10_001L), maxDurationMs = 10_000L
        )
        assertEquals(emptyList<MessageAttachment>(), accepted)
        assertEquals(1, rejected)
    }

    @Test fun `video with unknown duration is let through rather than blocked`() {
        val v = video(1)
        val (accepted, rejected) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(v), mapOf(v.uri to null), maxDurationMs = 10_000L
        )
        assertEquals(listOf(v), accepted)
        assertEquals(0, rejected)
    }

    @Test fun `video missing from the durations map is let through rather than blocked`() {
        val v = video(1)
        val (accepted, rejected) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(v), emptyMap(), maxDurationMs = 10_000L
        )
        assertEquals(listOf(v), accepted)
        assertEquals(0, rejected)
    }

    @Test fun `mixed selection keeps images and short videos, drops only the long one`() {
        val short = video(1)
        val long = video(2)
        val (accepted, rejected) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(img, short, long),
            mapOf(short.uri to 5_000L, long.uri to 30_000L),
            maxDurationMs = 10_000L
        )
        assertEquals(listOf(img, short), accepted)
        assertEquals(1, rejected)
    }

    @Test fun `multiple over-cap videos are all rejected and counted`() {
        val v1 = video(1)
        val v2 = video(2)
        val (accepted, rejected) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(v1, v2),
            mapOf(v1.uri to 15_000L, v2.uri to 20_000L),
            maxDurationMs = 10_000L
        )
        assertEquals(emptyList<MessageAttachment>(), accepted)
        assertEquals(2, rejected)
    }

    @Test fun `original order is preserved among accepted attachments`() {
        val v1 = video(1)
        val v2 = video(2)
        val (accepted, _) = ThreadViewModel.partitionAttachmentsByDuration(
            listOf(v2, img, v1),
            mapOf(v1.uri to 1_000L, v2.uri to 2_000L),
            maxDurationMs = 10_000L
        )
        assertEquals(listOf(v2, img, v1), accepted)
    }
}
