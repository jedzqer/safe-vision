package com.safe.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX face detector with 5-point landmarks. Used to locate eyes for eye-mask rendering.
 */
class FaceLandmarkOnnxRunner(
    context: Context
) {
    private val environment = OrtEnvironment.getEnvironment()
    private val inputSize = 320
    private val session: OrtSession
    private val inputName: String
    private val inputArea = inputSize * inputSize
    private val floatValues = FloatArray(3 * inputArea)
    private val floatBuffer = FloatBuffer.wrap(floatValues)
    private val pixels = IntArray(inputArea)
    private val priors: FloatArray = buildPriors(inputSize)
    private val confidenceThreshold = DetectionConfig.FaceLandmark.CONFIDENCE_THRESHOLD
    private val nmsThreshold = DetectionConfig.FaceLandmark.NMS_THRESHOLD
    private val variance0 = DetectionConfig.FaceLandmark.VARIANCE_0
    private val variance1 = DetectionConfig.FaceLandmark.VARIANCE_1

    init {
        val modelFile = ensureModelFiles(context)
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(DetectionConfig.defaultCpuThreadCount())
            setInterOpNumThreads(1)
        }
        session = environment.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.iterator().next()
        DebugLogManager.addLog("模型加载", "人脸关键点模型加载成功: ${modelFile.name}")
    }

    fun run(bitmap: Bitmap): List<FaceDetection> {
        if (bitmap.width <= 1 || bitmap.height <= 1) return emptyList()
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scaled = Bitmap.createScaledBitmap(source, inputSize, inputSize, true)
        try {
            scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            for (i in 0 until inputArea) {
                val pixel = pixels[i]
                val r = ((pixel shr 16) and 0xFF).toFloat() - 104f
                val g = ((pixel shr 8) and 0xFF).toFloat() - 117f
                val b = (pixel and 0xFF).toFloat() - 123f
                floatValues[i] = r
                floatValues[i + inputArea] = g
                floatValues[i + inputArea * 2] = b
            }
            floatBuffer.position(0)
            floatBuffer.limit(floatValues.size)
            val input = OnnxTensor.createTensor(
                environment,
                floatBuffer,
                longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            )
            input.use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    if (result.size() < 3) return emptyList()
                    val loc = extract2D(result[0].value)
                    val conf = extract2D(result[1].value)
                    val landm = extract2D(result[2].value)
                    if (loc.isEmpty() || conf.isEmpty() || landm.isEmpty()) return emptyList()
                    val decoded = decodeDetections(loc, conf, landm, source.width, source.height)
                    return nonMaxSuppression(decoded)
                }
            }
        } finally {
            if (scaled !== source && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (source !== bitmap && !source.isRecycled) {
                source.recycle()
            }
        }
    }

    private fun decodeDetections(
        loc: Array<FloatArray>,
        conf: Array<FloatArray>,
        landm: Array<FloatArray>,
        srcWidth: Int,
        srcHeight: Int
    ): List<FaceDetection> {
        val priorCount = priors.size / 4
        val count = min(priorCount, min(loc.size, min(conf.size, landm.size)))
        val detections = ArrayList<FaceDetection>(count)
        val scaleX = srcWidth.toFloat()
        val scaleY = srcHeight.toFloat()

        for (i in 0 until count) {
            val locRow = loc[i]
            val confRow = conf[i]
            val landmRow = landm[i]
            if (locRow.size < 4 || confRow.size < 2 || landmRow.size < 10) continue

            val faceScore = softmaxFaceScore(confRow[0], confRow[1])
            if (faceScore < confidenceThreshold) continue

            val priorOffset = i * 4
            val cx = priors[priorOffset]
            val cy = priors[priorOffset + 1]
            val pw = priors[priorOffset + 2]
            val ph = priors[priorOffset + 3]

            val decodeCx = cx + locRow[0] * variance0 * pw
            val decodeCy = cy + locRow[1] * variance0 * ph
            val decodeW = pw * exp(locRow[2] * variance1)
            val decodeH = ph * exp(locRow[3] * variance1)
            val left = ((decodeCx - decodeW / 2f) * scaleX).coerceIn(0f, scaleX)
            val top = ((decodeCy - decodeH / 2f) * scaleY).coerceIn(0f, scaleY)
            val right = ((decodeCx + decodeW / 2f) * scaleX).coerceIn(0f, scaleX)
            val bottom = ((decodeCy + decodeH / 2f) * scaleY).coerceIn(0f, scaleY)
            if (right <= left || bottom <= top) continue

            val leftEye = decodeLandmarkPoint(landmRow[0], landmRow[1], cx, cy, pw, ph, scaleX, scaleY)
            val rightEye = decodeLandmarkPoint(landmRow[2], landmRow[3], cx, cy, pw, ph, scaleX, scaleY)
            val faceRect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            val eyeBarRect = BlurEffects.eyeBarFromEyes(faceRect, leftEye, rightEye, srcWidth, srcHeight)
            detections.add(
                FaceDetection(
                    box = RectF(left, top, right, bottom),
                    score = faceScore,
                    leftEye = leftEye,
                    rightEye = rightEye,
                    eyeBar = RectF(
                        eyeBarRect.left.toFloat(),
                        eyeBarRect.top.toFloat(),
                        eyeBarRect.right.toFloat(),
                        eyeBarRect.bottom.toFloat()
                    )
                )
            )
        }
        return detections.sortedByDescending { it.score }
    }

    private fun decodeLandmarkPoint(
        dx: Float,
        dy: Float,
        cx: Float,
        cy: Float,
        pw: Float,
        ph: Float,
        scaleX: Float,
        scaleY: Float
    ): PointF {
        val x = (cx + dx * variance0 * pw) * scaleX
        val y = (cy + dy * variance0 * ph) * scaleY
        return PointF(x.coerceIn(0f, scaleX), y.coerceIn(0f, scaleY))
    }

    private fun softmaxFaceScore(background: Float, face: Float): Float {
        val maxVal = max(background, face)
        val eb = exp(background - maxVal)
        val ef = exp(face - maxVal)
        val sum = eb + ef
        return if (sum <= 0f) 0f else ef / sum
    }

    private fun nonMaxSuppression(candidates: List<FaceDetection>): List<FaceDetection> {
        if (candidates.isEmpty()) return emptyList()
        val selected = mutableListOf<FaceDetection>()
        candidates.forEach { candidate ->
            var keep = true
            for (existing in selected) {
                if (iou(candidate.box, existing.box) > nmsThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) selected.add(candidate)
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight
        if (interArea <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    private fun extract2D(value: Any?): Array<FloatArray> {
        val root = value as? Array<*> ?: return emptyArray()
        if (root.isEmpty()) return emptyArray()
        val first = root[0]
        return when (first) {
            is Array<*> -> first.mapNotNull { it as? FloatArray }.toTypedArray()
            is FloatArray -> root.mapNotNull { it as? FloatArray }.toTypedArray()
            else -> emptyArray()
        }
    }

    private fun ensureModelFiles(context: Context): File {
        val modelFile = File(context.cacheDir, "mobilenet0.25_faceDetector.onnx")
        val dataFile = File(context.cacheDir, "faceDetector.onnx.data")
        if (!modelFile.exists()) {
            context.assets.open("mobilenet0.25_faceDetector.onnx").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!dataFile.exists()) {
            context.assets.open("faceDetector.onnx.data").use { input ->
                dataFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return modelFile
    }

    private fun buildPriors(imageSize: Int): FloatArray {
        val minSizes = arrayOf(
            intArrayOf(10, 20),
            intArrayOf(32, 64),
            intArrayOf(128, 256)
        )
        val steps = intArrayOf(8, 16, 32)
        val anchors = ArrayList<Float>(4200 * 4)
        for (k in steps.indices) {
            val step = steps[k]
            val featureH = ceil(imageSize.toDouble() / step.toDouble()).toInt()
            val featureW = featureH
            for (i in 0 until featureH) {
                for (j in 0 until featureW) {
                    for (minSize in minSizes[k]) {
                        val s = minSize.toFloat() / imageSize.toFloat()
                        val cx = (j + 0.5f) * step / imageSize.toFloat()
                        val cy = (i + 0.5f) * step / imageSize.toFloat()
                        anchors.add(cx)
                        anchors.add(cy)
                        anchors.add(s)
                        anchors.add(s)
                    }
                }
            }
        }
        return FloatArray(anchors.size) { anchors[it] }
    }

    data class FaceDetection(
        val box: RectF,
        val score: Float,
        val leftEye: PointF,
        val rightEye: PointF,
        val eyeBar: RectF
    )
}
