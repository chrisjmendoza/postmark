package com.plusorminustwo.postmark.service.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure orphan-cleanup decision in [ChatBackgroundImageStore].
 *
 * The bitmap I/O ([ChatBackgroundImageStore.save] / [fileFor][ChatBackgroundImageStore.fileFor]
 * / [delete][ChatBackgroundImageStore.delete]) depends on Android's BitmapFactory and a real
 * filesDir, so it isn't JVM-testable — left untested here for the same reason
 * `MmsManagerWrapper.compressImage` is. Only [shouldDeleteImage] (a pure function) is covered.
 */
class ChatBackgroundImageStoreTest {

    @Test
    fun `delete only when neither a thread nor the global default references the image`() {
        // Truth table: delete iff !referencedByAnyThread && !isGlobalDefault.
        assertTrue(ChatBackgroundImageStore.shouldDeleteImage(referencedByAnyThread = false, isGlobalDefault = false))
        assertFalse(ChatBackgroundImageStore.shouldDeleteImage(referencedByAnyThread = true, isGlobalDefault = false))
        assertFalse(ChatBackgroundImageStore.shouldDeleteImage(referencedByAnyThread = false, isGlobalDefault = true))
        assertFalse(ChatBackgroundImageStore.shouldDeleteImage(referencedByAnyThread = true, isGlobalDefault = true))
    }

    @Test
    fun `calculateInSampleSize subsamples large images and leaves small ones at 1`() {
        // Both halved dims must stay >= maxDim to keep subsampling (a final exact scale
        // in save() finishes the job), so the sample stays conservative.
        assertEquals(1, ChatBackgroundImageStore.calculateInSampleSize(1000, 800, 1440))
        assertEquals(1, ChatBackgroundImageStore.calculateInSampleSize(2000, 1000, 1440))
        assertEquals(2, ChatBackgroundImageStore.calculateInSampleSize(4000, 3000, 1440))
        assertEquals(4, ChatBackgroundImageStore.calculateInSampleSize(8000, 6000, 1440))
    }
}
