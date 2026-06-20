package com.safe.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import kotlin.math.exp
import kotlin.math.roundToInt

class BodyPartSegmentationRunner(
    context: Context
) : Closeable {
    private val appContext = context.applicationContext
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inputSize = 512
    private val inputArea = inputSize * inputSize
    private val optimizedModelPath = File(context.cacheDir, "seg_clothe_optimized.onnx").absolutePath
    private val cpuThreads = DetectionConfig.defaultCpuThreadCount()
    private val runLock = Any()

    init {
        val modelFile = ensureModelFile()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(cpuThreads)
            setInterOpNumThreads(1)
            runCatching { setOptimizedModelFilePath(optimizedModelPath) }
        }
        session = environment.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.iterator().next()
    }

    fun run(bitmap: Bitmap): List<BodyPartMaskRegion> {
        return synchronized(runLock) {
            val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val input = preprocess(scaled)
            val logits = input.use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (((result[0].value as Array<*>)[0]) as Array<Array<FloatArray>>)
                }
            }
            try {
                val classified = upsampleAndClassify(logits, bitmap.width, bitmap.height)
                return extractRegions(
                    classified.labelMap,
                    classified.alphaMap,
                    bitmap.width,
                    bitmap.height
                )
            } finally {
                if (!scaled.isRecycled) scaled.recycle()
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val pixels = IntArray(inputArea)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val floats = FloatArray(3 * inputArea)
        for (i in 0 until inputArea) {
            val pixel = pixels[i]
            val r = Color.red(pixel) / 255f
            val g = Color.green(pixel) / 255f
            val b = Color.blue(pixel) / 255f
            floats[i] = (r - 0.485f) / 0.229f
            floats[i + inputArea] = (g - 0.456f) / 0.224f
            floats[i + inputArea * 2] = (b - 0.406f) / 0.225f
        }
        val buffer = FloatBuffer.wrap(floats)
        return OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )
    }

    private data class ClassifiedSegmentation(
        val labelMap: IntArray,
        val alphaMap: IntArray
    )

    private fun upsampleAndClassify(
        logits: Array<Array<FloatArray>>,
        targetWidth: Int,
        targetHeight: Int
    ): ClassifiedSegmentation {
        val classes = logits.size
        val srcHeight = logits.firstOrNull()?.size ?: 0
        val srcWidth = logits.firstOrNull()?.firstOrNull()?.size ?: 0
        if (classes == 0 || srcHeight == 0 || srcWidth == 0) {
            return ClassifiedSegmentation(
                labelMap = IntArray(targetWidth * targetHeight),
                alphaMap = IntArray(targetWidth * targetHeight)
            )
        }
        val labelMap = IntArray(targetWidth * targetHeight)
        val alphaMap = IntArray(targetWidth * targetHeight)
        val interpolatedLogits = FloatArray(classes)
        for (y in 0 until targetHeight) {
            val srcY = if (targetHeight <= 1) 0f else y * (srcHeight - 1f) / (targetHeight - 1f)
            val y0 = srcY.toInt().coerceIn(0, srcHeight - 1)
            val y1 = (y0 + 1).coerceIn(0, srcHeight - 1)
            val wy = srcY - y0
            for (x in 0 until targetWidth) {
                val srcX = if (targetWidth <= 1) 0f else x * (srcWidth - 1f) / (targetWidth - 1f)
                val x0 = srcX.toInt().coerceIn(0, srcWidth - 1)
                val x1 = (x0 + 1).coerceIn(0, srcWidth - 1)
                val wx = srcX - x0
                var bestLabel = 0
                var bestValue = Float.NEGATIVE_INFINITY
                for (label in 0 until classes) {
                    val plane = logits[label]
                    val topLeft = plane[y0][x0]
                    val topRight = plane[y0][x1]
                    val bottomLeft = plane[y1][x0]
                    val bottomRight = plane[y1][x1]
                    val top = topLeft + (topRight - topLeft) * wx
                    val bottom = bottomLeft + (bottomRight - bottomLeft) * wx
                    val value = top + (bottom - top) * wy
                    interpolatedLogits[label] = value
                    if (value > bestValue) {
                        bestValue = value
                        bestLabel = label
                    }
                }
                val alpha = if (bestLabel <= 0) {
                    0
                } else {
                    var softmaxDenominator = 0.0
                    for (label in 0 until classes) {
                        softmaxDenominator += exp((interpolatedLogits[label] - bestValue).toDouble())
                    }
                    val probability = if (softmaxDenominator > 0.0) {
                        1.0 / softmaxDenominator
                    } else {
                        0.0
                    }
                    (probability * 255.0).roundToInt().coerceIn(0, 255)
                }
                val index = y * targetWidth + x
                labelMap[index] = bestLabel
                alphaMap[index] = alpha
            }
        }
        return ClassifiedSegmentation(labelMap = labelMap, alphaMap = alphaMap)
    }

    private fun extractRegions(
        labelMap: IntArray,
        alphaMap: IntArray,
        width: Int,
        height: Int
    ): List<BodyPartMaskRegion> {
        val aggregateMasks = LinkedHashMap<String, IntArray>()
        val bounds = LinkedHashMap<String, IntArray>()

        for (index in labelMap.indices) {
            val labelId = labelMap[index]
            if (labelId <= 0) continue
            val rawLabel = rawModelLabelForId(labelId) ?: continue
            val aggregateLabel = DetectionConfig.mapRawBodyLabelToAggregateLabel(rawLabel) ?: continue
            val alpha = alphaMap[index].coerceIn(0, 255)
            if (alpha <= 0) continue

            val pixels = aggregateMasks.getOrPut(aggregateLabel) { IntArray(width * height) }
            pixels[index] = maxOf(pixels[index], alpha)

            val x = index % width
            val y = index / width
            val currentBounds = bounds.getOrPut(aggregateLabel) { intArrayOf(width, height, -1, -1) }
            if (x < currentBounds[0]) currentBounds[0] = x
            if (y < currentBounds[1]) currentBounds[1] = y
            if (x > currentBounds[2]) currentBounds[2] = x
            if (y > currentBounds[3]) currentBounds[3] = y
        }

        return aggregateMasks.mapNotNull { (label, pixels) ->
            val labelBounds = bounds[label] ?: return@mapNotNull null
            val minX = labelBounds[0]
            val minY = labelBounds[1]
            val maxX = labelBounds[2]
            val maxY = labelBounds[3]
            if (maxX < minX || maxY < minY) return@mapNotNull null

            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            val croppedPixels = IntArray(boxWidth * boxHeight)
            for (y in minY..maxY) {
                val srcOffset = y * width
                val dstOffset = (y - minY) * boxWidth
                for (x in minX..maxX) {
                    val alpha = pixels[srcOffset + x]
                    if (alpha > 0) {
                        croppedPixels[dstOffset + (x - minX)] = Color.argb(alpha, 255, 255, 255)
                    }
                }
            }

            val mask = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ALPHA_8).apply {
                setPixels(croppedPixels, 0, boxWidth, 0, 0, boxWidth, boxHeight)
            }
            BodyPartMaskRegion(
                label = label,
                box = Rect(minX, minY, maxX + 1, maxY + 1),
                maskAlphaBitmap = mask
            )
        }
    }

    private fun rawModelLabelForId(labelId: Int): String? {
        return when (labelId) {
            1 -> "Hat"
            2 -> "Hair"
            3 -> "Sunglasses"
            4 -> "Upper-clothes"
            5 -> "Skirt"
            6 -> "Pants"
            7 -> "Dress"
            8 -> "Belt"
            9 -> "Left-shoe"
            10 -> "Right-shoe"
            11 -> "Face"
            12 -> "Left-leg"
            13 -> "Right-leg"
            14 -> "Left-arm"
            15 -> "Right-arm"
            16 -> "Bag"
            17 -> "Scarf"
            else -> null
        }
    }

    private fun ensureModelFile(): File {
        val modelFile = File(appContext.cacheDir, "seg_clothe_model.onnx")
        if (!modelFile.exists()) {
            appContext.assets.open("segformer_body_seg.onnx").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return modelFile
    }

    override fun close() {
        runCatching { session.close() }
    }
}
