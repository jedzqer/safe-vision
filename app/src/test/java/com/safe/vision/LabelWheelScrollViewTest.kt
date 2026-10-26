package com.safe.vision

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.app.Activity
import android.view.MotionEvent
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelWheelScrollViewTest {
    private fun scenario(block: (LabelWheelScrollView, MutableList<String>, Button) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_SafeVision)
        val selected = mutableListOf<String>()
        val view = LabelWheelScrollView(activity).apply {
            labels = { (0..6).map { "$it" to "Label $it" } }
            onLabelSelected = { selected.add(it) }
        }
        val button = Button(activity).apply { setOnClickListener { selected.add("tap") } }
        view.addView(button)
        activity.setContentView(view)
        view.layout(0, 0, 400, 600)
        button.layout(0, 0, 400, 100)
        try { block(view, selected, button) } finally { controller.pause().stop().destroy() }
    }

    private fun touch(view: LabelWheelScrollView, action: Int, y: Float, x: Float = 40f) {
        MotionEvent.obtain(0, 100, action, x, y, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    @Test fun dragFromChipCancelsClickAndReleaseOpensMiddleLabelOnce() = scenario { view, selected, _ ->
        touch(view, MotionEvent.ACTION_DOWN, 50f)
        touch(view, MotionEvent.ACTION_MOVE, 10f)
        touch(view, MotionEvent.ACTION_UP, 10f)
        assertEquals(listOf("3"), selected)
    }

    @Test fun dragFromBlankAreaMovesSelectionAndClampsAtEnd() = scenario { view, selected, _ ->
        touch(view, MotionEvent.ACTION_DOWN, 300f)
        touch(view, MotionEvent.ACTION_MOVE, 260f)
        touch(view, MotionEvent.ACTION_MOVE, -2000f)
        touch(view, MotionEvent.ACTION_UP, -2000f)
        assertEquals(listOf("6"), selected)
    }

    @Test fun pairsAreAdjacentCoveredFirstAndSingletonsRemainAlone() {
        val labels = listOf("BELLY_EXPOSED", "FACE", "FEET_COVERED", "BELLY_COVERED")
        assertEquals(
            listOf(listOf("BELLY_COVERED", "BELLY_EXPOSED"), listOf("FACE"), listOf("FEET_COVERED")),
            labelWheelRows(labels.map { it to "same display name" }).map { row -> row.map { it.first } }
        )
    }

    @Test fun horizontalMovementSelectsEitherVariantOnRelease() = scenario { view, selected, _ ->
        view.labels = { listOf("BELLY_EXPOSED", "BELLY_COVERED").map { it to it } }
        touch(view, MotionEvent.ACTION_DOWN, 50f)
        touch(view, MotionEvent.ACTION_MOVE, 10f)
        touch(view, MotionEvent.ACTION_UP, 10f, 200f)
        assertEquals(listOf("BELLY_EXPOSED"), selected)
        selected.clear()
        touch(view, MotionEvent.ACTION_DOWN, 50f)
        touch(view, MotionEvent.ACTION_MOVE, 10f)
        touch(view, MotionEvent.ACTION_MOVE, 10f, 200f)
        touch(view, MotionEvent.ACTION_UP, 10f, 40f)
        assertEquals(listOf("BELLY_COVERED"), selected)
    }

    @Test fun singletonRemainsSelectableAfterMovingRight() = scenario { view, selected, _ ->
        touch(view, MotionEvent.ACTION_DOWN, 50f)
        touch(view, MotionEvent.ACTION_MOVE, 10f)
        touch(view, MotionEvent.ACTION_UP, 10f, 200f)
        assertEquals(listOf("3"), selected)
    }

    @Test fun cancellationAndLifecycleDismissNeverSelect() = scenario { view, selected, _ ->
        touch(view, MotionEvent.ACTION_DOWN, 50f)
        touch(view, MotionEvent.ACTION_MOVE, 10f)
        touch(view, MotionEvent.ACTION_CANCEL, 10f)
        assertTrue(selected.isEmpty())
        touch(view, MotionEvent.ACTION_DOWN, 50f)
        touch(view, MotionEvent.ACTION_MOVE, 10f)
        view.dismissWheel()
        touch(view, MotionEvent.ACTION_UP, 10f)
        assertTrue(selected.isEmpty())
    }

    @Test fun simpleTapKeepsExistingEditorEntry() = scenario { view, selected, _ ->
        touch(view, MotionEvent.ACTION_DOWN, 50f)
        touch(view, MotionEvent.ACTION_UP, 50f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("tap"), selected)
    }
}
