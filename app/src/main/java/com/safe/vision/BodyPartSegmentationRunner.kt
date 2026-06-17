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
import java.util.ArrayDeque

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
                val labelMap = upsampleAndArgmax(logits, bitmap.width, bitmap.height)
                return extractRegions(labelMap, bitmap.width, bitmap.height)
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

    private fun upsampleAndArgmax(
        logits: Array<Array<FloatArray>>,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        val classes = logits.size
        val srcHeight = logits.firstOrNull()?.size ?: 0
        val srcWidth = logits.firstOrNull()?.firstOrNull()?.size ?: 0
        if (classes == 0 || srcHeight == 0 || srcWidth == 0) return IntArray(targetWidth * targetHeight)
        val out = IntArray(targetWidth * targetHeight)
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
                    if (value > bestValue) {
                        bestValue = value
                        bestLabel = label
                    }
                }
                out[y * targetWidth + x] = bestLabel
            }
        }
        return out
    }

    private fun extractRegions(labelMap: IntArray, width: Int, height: Int): List<BodyPartMaskRegion> {
        val visited = BooleanArray(labelMap.size)
        val results = mutableListOf<BodyPartMaskRegion>()
        val queue = ArrayDeque<Int>()
        val neighbors = intArrayOf(-1, 1, 0, 0)
        val neighborYs = intArrayOf(0, 0, -1, 1)

        for (index in labelMap.indices) {
            val labelId = labelMap[index]
            if (labelId <= 0 || visited[index]) continue
            val label = DetectionConfig.BODY_LABELS.getOrNull(labelId - 1) ?: continue
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            val pixels = mutableListOf<Int>()
            visited[index] = true
            queue.add(index)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val x = current % width
                val y = current / width
                pixels.add(current)
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                for (n in 0 until 4) {
                    val nx = x + neighbors[n]
                    val ny = y + neighborYs[n]
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (visited[next] || labelMap[next] != labelId) continue
                    visited[next] = true
                    queue.add(next)
                }
            }
            if (maxX < minX || maxY < minY) continue
            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            val mask = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ALPHA_8)
            val maskPixels = IntArray(boxWidth * boxHeight)
            for (pixelIndex in pixels) {
                val x = pixelIndex % width - minX
                val y = pixelIndex / width - minY
                maskPixels[y * boxWidth + x] = Color.argb(255, 255, 255, 255)
            }
            mask.setPixels(maskPixels, 0, boxWidth, 0, 0, boxWidth, boxHeight)
            results.add(
                BodyPartMaskRegion(
                    label = label,
                    box = Rect(minX, minY, maxX + 1, maxY + 1),
                    maskAlphaBitmap = mask
                )
            )
        }
        return results
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
