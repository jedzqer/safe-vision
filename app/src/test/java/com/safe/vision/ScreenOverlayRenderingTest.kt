package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenOverlayRenderingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val profile = DetectionConfig.LabelProfile.STANDARD
    private val label = "FACE_MALE"
    private lateinit var settings: PrivacySettingsManager
    private lateinit var renderer: ScreenPrivacyMaskRenderer

    @Before fun setup() {
        context.getSharedPreferences("privacy_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = PrivacySettingsManager.getInstance(context)
        settings.setBlockedLabels(profile, listOf(label))
        settings.setLabelReverse(label, false)
        settings.setMaskScale(1f)
        settings.setCircularMaskEnabled(false)
        settings.setMaskOutlineEnabled(false)
        settings.setBlurMode(profile, PrivacySettingsManager.BLUR_MODE_BLACK)
        renderer = ScreenPrivacyMaskRenderer(context)
    }

    private fun source() = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    private fun render(source: Bitmap, mode: ScreenOverlayMode = ScreenOverlayMode.ACCESSIBILITY,
                       left: Float = 10f): ScreenPrivacyMaskRenderer.OverlayFrame =
        requireNotNull(renderer.render(source,
            listOf(YoloOnnxRunner.Detection(label, 0.99f, RectF(left, 10f, left + 30f, 40f))),
            profile, mode, 2f, 2f))

    private fun draw(frame: ScreenPrivacyMaskRenderer.OverlayFrame): Bitmap {
        val output = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        ScreenMaskOverlayView(context).apply {
            layout(0, 0, 160, 160)
            bindFrame(frame, 0, 0, 2f, 2f)
            draw(Canvas(output))
        }
        return output
    }

    @Test fun blackMaskUsesGeometryAndScalesWithoutRetainingSourcePixels() {
        val source = source()
        val frame = render(source)
        assertNull(frame.preRenderedMaskBitmap)
        assertNull(frame.drawTasks.single().renderedBitmap)
        source.recycle() // Geometry is independent of the reusable capture buffer.
        val output = draw(frame)
        assertEquals(Color.BLACK, output.getPixel(30, 30))
        assertEquals(Color.TRANSPARENT, output.getPixel(10, 10))
        assertEquals(Color.TRANSPARENT, output.getPixel(90, 90))
    }

    @Test fun patchesSurviveCaptureReuseAndFrameReplacement() {
        settings.setBlurMode(profile, PrivacySettingsManager.BLUR_MODE_MOSAIC)
        val source = source()
        val old = render(source)
        val patch = requireNotNull(old.drawTasks.single().renderedBitmap)
        assertEquals(30, patch.width)
        assertNull(old.preRenderedMaskBitmap)
        source.eraseColor(Color.BLUE)
        val next = render(source)
        assertEquals(Color.RED, draw(old).getPixel(30, 30))
        assertEquals(Color.BLUE, draw(next).getPixel(30, 30))
        old.release()
        old.release()
        assertFalse(patch.isRecycled) // RenderThread may still hold the previous display list.
        assertFalse(source.isRecycled)
        assertTrue(old.drawTasks.isEmpty())
    }

    @Test fun regionViewUsesWindowOriginAndClearsOnRelease() {
        val frame = render(source(), ScreenOverlayMode.SYSTEM_ALERT_WINDOW)
        val view = ScreenMaskOverlayView(context)
        view.layout(0, 0, 60, 60)
        view.bindRegionTask(frame.drawTasks.single(), 20, 20, 2f, 2f)
        val output = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(output))
        assertEquals(Color.BLACK, output.getPixel(0, 0))
        assertEquals(Color.BLACK, output.getPixel(59, 59))
        view.release()
        output.eraseColor(Color.TRANSPARENT)
        view.draw(Canvas(output))
        assertEquals(Color.TRANSPARENT, output.getPixel(30, 30))
    }

    @Test fun reverseMaskStillClearsDetectedRegion() {
        settings.setLabelReverse(label, true)
        for (preRender in listOf(false, true)) {
            settings.setReversePreRenderEnabled(preRender)
            val frame = render(source())
            assertTrue(frame.requiresFullscreenOverlay)
            assertNotNull(frame.preRenderedMaskBitmap)
            val output = draw(frame)
            assertEquals(Color.TRANSPARENT, output.getPixel(30, 30))
            assertEquals(Color.BLACK, output.getPixel(100, 100))
        }
    }

    @Test fun stickerMovementReusesPatchButShapeChangeDoesNot() {
        settings.setBlurMode(profile, PrivacySettingsManager.BLUR_MODE_STICKER)
        val source = source()
        val first = render(source)
        val patch = requireNotNull(first.drawTasks.single().renderedBitmap)
        val moved = render(source, left = 20f)
        assertSame(patch, moved.drawTasks.single().renderedBitmap)
        first.release()
        assertFalse(patch.isRecycled)
        settings.setCircularMaskEnabled(true)
        val circular = render(source)
        assertNotSame(patch, circular.drawTasks.single().renderedBitmap)
    }

    @Test fun regionWindowsAreHiddenBoundedReusedAndRemovedOnStop() {
        val manager = context.getSystemService(WindowManager::class.java)
        val shadow = Shadow.extract<ShadowWindowManagerImpl>(manager)
        val host = ScreenOverlayWindowHost(context, manager,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, touchThrough = false)
        val metrics = OverlayMetrics(160, 160, 160, 0, 0)
        val frame = render(source(), ScreenOverlayMode.SYSTEM_ALERT_WINDOW)
        val many = ScreenPrivacyMaskRenderer.OverlayFrame(80, 80,
            List(6) { frame.drawTasks.single().copy(label = "target-$it") },
            null, emptyList(), false)
        try {
            host.showRegionOverlays(many, metrics)
            assertEquals(6, shadow.views.size)
            shadow.views.forEach { view ->
                val params = view.layoutParams as WindowManager.LayoutParams
                assertEquals(60, params.width)
                assertEquals(60, params.height)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
            }
            host.clearMaskOverlays()
            val retained = shadow.views.toList()
            assertEquals(4, retained.size)
            assertTrue(retained.all { it.visibility == View.INVISIBLE })
            host.showRegionOverlays(render(source(), ScreenOverlayMode.SYSTEM_ALERT_WINDOW, 40f), metrics)
            assertEquals(4, shadow.views.size)
            assertTrue(shadow.views.all { it in retained })
            assertEquals(1, shadow.views.count { it.visibility == View.VISIBLE })
        } finally {
            host.removeOverlayViews()
        }
        assertTrue(shadow.views.isEmpty())
    }

}
