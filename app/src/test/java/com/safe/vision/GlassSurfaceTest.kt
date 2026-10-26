package com.safe.vision

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassSurfaceTest {
    @Test fun themedLayoutsInflateAndDrawAndButtonsKeepClickSemantics() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            for (theme in listOf(R.style.Theme_SafeVision, R.style.Theme_SafeVision_Pastel,
                R.style.Theme_SafeVision_DeepSea, R.style.Theme_SafeVision_Custom)) {
                activity.setTheme(theme)
                for (layout in listOf(R.layout.activity_main, R.layout.fragment_settings,
                    R.layout.fragment_image_viewer, R.layout.fragment_gallery_folder_fullscreen,
                    R.layout.item_gallery_folder_card, R.layout.dialog_viewer_actions,
                    R.layout.dialog_viewer_rotation_sheet)) {
                    val root = LayoutInflater.from(activity).inflate(layout, FrameLayout(activity), false)
                    root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 480, 800)
                    val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    bitmap.recycle()
                }
                val button = GlassButton(activity)
                var clicks = 0
                button.setOnClickListener { clicks++ }
                assertTrue(button.performClick())
                assertEquals(1, clicks)
                assertTrue(button.isClickable)
                assertNotNull(button.backgroundTintList)
                button.isEnabled = false
                assertFalse(button.isEnabled)
                val dialog = DialogUtils.builder(activity).setTitle("Title").setMessage("Message")
                    .setPositiveButton(android.R.string.ok, null).create()
                dialog.show()
                assertTrue(dialog.isShowing)
                assertEquals(R.style.Animation_SafeVision_Dialog, dialog.window!!.attributes.windowAnimations)
                dialog.dismiss()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
