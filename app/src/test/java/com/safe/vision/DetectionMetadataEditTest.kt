package com.safe.vision

import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectionMetadataEditTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val width = 1000
    private val height = 1000
    private lateinit var file: File

    @Before
    fun writeFixture() {
        file = tmp.newFile("sample.json")
        file.writeText(
            """
            {
              "schema_version": 2,
              "label_format": "standard",
              "detections": [
                {"class": "FACE_FEMALE", "box": [100, 100, 400, 400]},
                {"class": "FEMALE_BREAST_EXPOSED", "box": [600, 600, 200, 200]}
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    private fun openEditor(): Pair<MutableList<EditableDetection>, DetectionEditorOverlayView> {
        val items = DetectionMetadataIo.read(file, width, height)
        val overlay = DetectionEditorOverlayView(RuntimeEnvironment.getApplication())
        overlay.eyeModeResolver = { DetectionConfig.isEyeRegionLabel(it.label) }
        overlay.setEditorData(items, Matrix(), width, height)
        return items to overlay
    }

    private fun save(items: List<EditableDetection>): List<EditableDetection> {
        DetectionMetadataIo.write(file, items)
        return DetectionMetadataIo.read(file, width, height)
    }

    private fun List<EditableDetection>.byLabel(label: String) = first { it.label == label }

    private fun touch(overlay: DetectionEditorOverlayView, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        overlay.onTouchEvent(event)
        event.recycle()
    }

    @Test
    fun rotatedBodyBoxIsPersisted() {
        val (items, overlay) = openEditor()
        overlay.updateBoxRotation(items.byLabel("FEMALE_BREAST_EXPOSED").id, 30f)

        val reloaded = save(items)

        assertEquals(30f, reloaded.byLabel("FEMALE_BREAST_EXPOSED").boxRotationDegrees!!, 0.01f)
    }

    @Test
    fun resizedBodyBoxIsPersisted() {
        val (items, overlay) = openEditor()
        val breast = items.byLabel("FEMALE_BREAST_EXPOSED")
        overlay.enableResizeMode(breast.id)
        touch(overlay, MotionEvent.ACTION_DOWN, breast.rect.right - 1f, breast.rect.bottom - 1f)
        touch(overlay, MotionEvent.ACTION_MOVE, breast.rect.right + 60f, breast.rect.bottom + 60f)
        touch(overlay, MotionEvent.ACTION_UP, breast.rect.right + 60f, breast.rect.bottom + 60f)

        val reloaded = save(items)

        assertTrue(reloaded.byLabel("FEMALE_BREAST_EXPOSED").rect.width() > 250f)
    }

    @Test
    fun rotatedEyeRegionIsPersistedWithoutRotatingFace() {
        val (items, overlay) = openEditor()
        overlay.updateBoxRotation(items.byLabel(DetectionConfig.EYE_REGION_LABEL).id, 25f)

        val reloaded = save(items)

        assertEquals(25f, reloaded.byLabel(DetectionConfig.EYE_REGION_LABEL).boxRotationDegrees!!, 0.01f)
        assertEquals(0f, reloaded.byLabel("FACE_FEMALE").boxRotationDegrees ?: 0f, 0.01f)
    }

    @Test
    fun editedEyeRegionSurvivesSecondSaveWithoutRotatingFace() {
        val (items, overlay) = openEditor()
        overlay.updateBoxRotation(items.byLabel(DetectionConfig.EYE_REGION_LABEL).id, 25f)
        save(items)

        val reopened = DetectionMetadataIo.read(file, width, height)
        val reloaded = save(reopened)

        assertEquals(25f, reloaded.byLabel(DetectionConfig.EYE_REGION_LABEL).boxRotationDegrees!!, 0.01f)
        assertEquals(0f, reloaded.byLabel("FACE_FEMALE").boxRotationDegrees ?: 0f, 0.01f)
    }

    @Test
    fun resizedEyeRegionIsPersisted() {
        val (items, overlay) = openEditor()
        val eye = items.byLabel(DetectionConfig.EYE_REGION_LABEL)
        val originalWidth = eye.rect.width()
        val right = eye.rect.right
        val bottom = eye.rect.bottom
        overlay.enableResizeMode(eye.id)
        touch(overlay, MotionEvent.ACTION_DOWN, right - 1f, bottom - 1f)
        touch(overlay, MotionEvent.ACTION_MOVE, right + 40f, bottom + 40f)
        touch(overlay, MotionEvent.ACTION_UP, right + 40f, bottom + 40f)

        val reloaded = save(items)

        assertTrue(reloaded.byLabel(DetectionConfig.EYE_REGION_LABEL).rect.width() > originalWidth + 20f)
    }

    @Test
    fun untouchedEyeRegionStaysDerivedFromFace() {
        val (items, _) = openEditor()

        save(items)

        val face = JSONObject(file.readText()).getJSONArray("detections").getJSONObject(0)
        assertFalse(face.has("eye_bar"))
        assertFalse(face.has("eye_bar_rotation"))
        assertNotNull(DetectionMetadataIo.read(file, width, height).byLabel(DetectionConfig.EYE_REGION_LABEL))
    }
}
