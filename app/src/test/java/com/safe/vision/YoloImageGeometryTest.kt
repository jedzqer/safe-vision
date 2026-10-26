package com.safe.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoloImageGeometryTest {
    @Test
    fun restoresNormalizedDetectionToPortraitCapturePixels() {
        val geometry = YoloImageGeometry(480, 960)
        assertArrayEquals(
            floatArrayOf(96f, 240f, 384f, 720f),
            geometry.restoreBox(0.1f, 0.25f, 0.4f, 0.75f)!!,
            0.001f
        )
    }

    @Test
    fun clipsLandscapeDetectionAtImageBoundaryInsteadOfIncludingBottomPadding() {
        val geometry = YoloImageGeometry(960, 480)
        assertArrayEquals(
            floatArrayOf(240f, 96f, 720f, 480f),
            geometry.restoreBox(0.25f, 0.1f, 0.75f, 0.8f)!!,
            0.001f
        )
    }

    @Test
    fun clipsBoxCrossingTopLeftEdgeWithoutShiftingItsOppositeCorner() {
        val geometry = YoloImageGeometry(320, 320)
        assertArrayEquals(
            floatArrayOf(0f, 0f, 160f, 240f),
            geometry.restoreBox(-0.1f, -0.2f, 0.5f, 0.75f)!!,
            0.001f
        )
    }

    @Test
    fun discardsPaddingOnlyAndInvalidDetections() {
        val geometry = YoloImageGeometry(480, 960)
        assertNull(geometry.restoreBox(0.6f, 0.1f, 0.9f, 0.5f))
        assertNull(geometry.restoreBox(0.4f, 0.1f, 0.2f, 0.5f))
        assertNull(geometry.restoreBox(Float.NaN, 0.1f, 0.4f, 0.5f))
        assertNull(geometry.restoreBox(0.1f, 0.1f, Float.POSITIVE_INFINITY, 0.5f))
    }
}
