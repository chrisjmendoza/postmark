package com.plusorminustwo.postmark.service.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure orphan-cleanup decision in [ChatBackgroundImageStore].
 *
 * The bitmap I/O ([saveWithPlacement][ChatBackgroundImageStore.saveWithPlacement] /
 * [fileFor][ChatBackgroundImageStore.fileFor] / [delete][ChatBackgroundImageStore.delete])
 * depends on Android's BitmapFactory and a real filesDir, so it isn't JVM-testable — left
 * untested here for the same reason `MmsManagerWrapper.compressImage` is (the pure placement
 * geometry it bakes lives in [BackgroundPlacementMath][com.plusorminustwo.postmark.domain.customization.BackgroundPlacementMath],
 * covered by BackgroundPlacementTest). Only [shouldDeleteImage] (a pure function) is covered.
 */
class ChatBackgroundImageStoreTest {

    @Test
    fun `delete only when neither a thread nor a preference references the image`() {
        // Truth table: delete iff !referencedByAnyThread && !referencedByAnyPreference.
        // referencedByAnyPreference covers BOTH the global chat default and the home-screen
        // background — an image backing only the home screen must survive a chat-background
        // change, which is the case the second assertion below pins down.
        assertTrue(ChatBackgroundImageStore.shouldDeleteImage(referencedByAnyThread = false, referencedByAnyPreference = false))
        assertFalse(ChatBackgroundImageStore.shouldDeleteImage(referencedByAnyThread = false, referencedByAnyPreference = true))
        assertFalse(ChatBackgroundImageStore.shouldDeleteImage(referencedByAnyThread = true, referencedByAnyPreference = false))
        assertFalse(ChatBackgroundImageStore.shouldDeleteImage(referencedByAnyThread = true, referencedByAnyPreference = true))
    }

    @Test
    fun `calculateInSampleSize subsamples large images and leaves small ones at 1`() {
        // Both halved dims must stay >= maxDim to keep subsampling (a final exact scale
        // in decodeOriented() finishes the job), so the sample stays conservative.
        assertEquals(1, ChatBackgroundImageStore.calculateInSampleSize(1000, 800, 1440))
        assertEquals(1, ChatBackgroundImageStore.calculateInSampleSize(2000, 1000, 1440))
        assertEquals(2, ChatBackgroundImageStore.calculateInSampleSize(4000, 3000, 1440))
        assertEquals(4, ChatBackgroundImageStore.calculateInSampleSize(8000, 6000, 1440))
    }
}
