package com.safe.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight helper responsible for loading the YOLO ONNX model, preprocessing input bitmaps,
 * and returning structured detection metadata similar to the reference Python script.
 */
class YoloOnnxRunner(
    context: Context,
    private val variant: DetectionModelVariant = DetectionModelVariant.STANDARD
) {

    private val appContext = context.applicationContext
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val modelConfig = ModelConfig(
        modelFileName = variant.modelFileName,
        dataFileName = variant.dataFileName,
        inputSize = variant.inputSize,
        optimizedFileName = variant.optimizedFileName,
        label = variant.runtimeLabel
    )
    private val modelInputSize = modelConfig.inputSize
    private val session: OrtSession
    private val inputName: String
    private val executionProvider: String
    private val inputArea = modelInputSize * modelInputSize
    private val floatValues = FloatArray(3 * inputArea)
    private val floatBuffer = FloatBuffer.wrap(floatValues)
    private val pixels = IntArray(inputArea)
    private val optimizedModelPath: String =
        File(context.cacheDir, modelConfig.optimizedFileName).absolutePath
    private val cpuThreads = DetectionConfig.defaultCpuThreadCount()
    private var paddedBitmap: Bitmap? = null
    private var paddedCanvas: Canvas? = null
    private var resizedBitmap: Bitmap? = null
    private var resizedCanvas: Canvas? = null
    private val resizeSrcRect = Rect()
    private val resizeDstRect = Rect(0, 0, modelInputSize, modelInputSize)
    @Volatile
    private var faceLandmarkRunner: FaceLandmarkOnnxRunner? = null
    @Volatile
    private var faceLandmarkInitAttempted = false

    init {
        val modelFile = ensureModelFiles(context)
        val (createdSession, provider) = createSessionWithFallback(modelFile.absolutePath)
        session = createdSession
        executionProvider = provider
        inputName = session.inputNames.iterator().next()
        DebugLogManager.addLog("模型加载", "推理后端: $executionProvider (模型: ${modelConfig.label})")
    }

    fun run(bitmap: Bitmap, enrichFaceLandmarks: Boolean = true): List<Detection> {
        val startTime = System.currentTimeMillis()
        val (inputTensor, meta) = preprocess(bitmap)

        inputTensor.use { tensor ->
            val inferenceStart = System.currentTimeMillis()
            session.run(mapOf(inputName to tensor)).use { result ->
                val inferenceTime = System.currentTimeMillis() - inferenceStart
                if (result.size() < 1) {
                    DebugLogManager.addLog("模型检测", "错误: 模型输出为空")
                    return emptyList()
                }
                val outputTensor = result[0]
                val rawData = outputTensor.value
                val processedData = when (rawData) {
                    is Array<*> -> {
                        if (rawData.isNotEmpty() && rawData[0] is Array<*>) {
                            val batchData = rawData[0] as Array<*>
                            if (batchData.isNotEmpty() && batchData[0] is FloatArray) {
                                val cols = batchData.size
                                val rows = (batchData[0] as FloatArray).size
                                val transposed = Array(rows) { FloatArray(cols) }
                                
                                for (i in 0 until cols) {
                                    val col = batchData[i] as FloatArray
                                    for (j in 0 until rows) {
                                        transposed[j][i] = col[j]
                                    }
                                }
                                transposed
                            } else {
                                DebugLogManager.addLog("模型检测", "错误: 数据格式不匹配")
                                emptyArray()
                            }
                        } else {
                            DebugLogManager.addLog("模型检测", "错误: 批次数据为空")
                            emptyArray()
                        }
                    }
                    else -> {
                        DebugLogManager.addLog("模型检测", "错误: 未知的数据格式")
                        emptyArray()
                    }
                }
                var detections = postProcess(processedData, meta)
                if (enrichFaceLandmarks && detections.any { DetectionConfig.supportsFaceLandmarks(it.className) }) {
                    detections = attachFaceLandmarks(bitmap, detections)
                }
                val totalTime = System.currentTimeMillis() - startTime
                DebugLogManager.addLog(
                    "模型检测",
                    "检测完成: 输入=${bitmap.width}x${bitmap.height}, 推理=${inferenceTime}ms, 总耗时=${totalTime}ms, 结果=${detections.size}"
                )
                
                return detections
            }
        }
    }
    
    // 移除私有调试日志方法，直接使用DebugLogManager

    private fun preprocess(bitmap: Bitmap): Pair<OnnxTensor, PreprocessMeta> {
        val sourceBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val squareSize = max(width, height)
        
        // 复用 padded/resized 位图，减少内存分配
        if (paddedBitmap == null || paddedBitmap?.width != squareSize || paddedBitmap?.height != squareSize) {
            paddedBitmap = Bitmap.createBitmap(squareSize, squareSize, Bitmap.Config.ARGB_8888)
            paddedCanvas = Canvas(paddedBitmap!!)
        }
        paddedBitmap?.eraseColor(Color.BLACK)
        paddedCanvas?.drawBitmap(sourceBitmap, 0f, 0f, null)

        if (resizedBitmap == null) {
            resizedBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
            resizedCanvas = Canvas(resizedBitmap!!)
        }
        resizeSrcRect.set(0, 0, squareSize, squareSize)
        resizedCanvas?.drawBitmap(paddedBitmap!!, resizeSrcRect, resizeDstRect, null)

        resizedBitmap?.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)

        for (i in 0 until inputArea) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            floatValues[i] = r
            floatValues[i + inputArea] = g
            floatValues[i + inputArea * 2] = b
        }

        floatBuffer.position(0)
        floatBuffer.limit(floatValues.size)
        val tensor = OnnxTensor.createTensor(
            environment,
            floatBuffer,
            longArrayOf(1, 3, modelInputSize.toLong(), modelInputSize.toLong())
        )

        val meta = PreprocessMeta(
            originalWidth = width,
            originalHeight = height,
            squareSize = squareSize
        )
        return tensor to meta
    }

    private fun postProcess(raw: Array<FloatArray>, meta: PreprocessMeta): List<Detection> {
        val detections = mutableListOf<DetectionCandidate>()
        val rows = raw.size

        for (i in 0 until rows) {
            val row = raw[i]
            if (row.size < 5) continue
            val scores = row.copyOfRange(4, row.size)
            var maxScore = Float.NEGATIVE_INFINITY
            var classId = -1
            scores.forEachIndexed { index, score ->
                if (!score.isNaN() && score >= 0f && score > maxScore) {
                    maxScore = score
                    classId = index
                }
            }
            if (classId == -1) continue
            if (maxScore < DetectionConfig.SCORE_THRESHOLD) continue
            var x = row[0]
            var y = row[1]
            var w = row[2]
            var h = row[3]

            x = x - w / 2f
            y = y - h / 2f

            val xPad = (meta.squareSize - meta.originalWidth).toFloat()
            val yPad = (meta.squareSize - meta.originalHeight).toFloat()
            x = x * (meta.originalWidth + xPad) / modelInputSize
            y = y * (meta.originalHeight + yPad) / modelInputSize
            w = w * (meta.originalWidth + xPad) / modelInputSize
            h = h * (meta.originalHeight + yPad) / modelInputSize

            x = x.coerceIn(0f, meta.originalWidth.toFloat())
            y = y.coerceIn(0f, meta.originalHeight.toFloat())
            w = min(w, meta.originalWidth - x)
            h = min(h, meta.originalHeight - y)

            detections.add(
                DetectionCandidate(
                    score = maxScore,
                    classId = classId,
                    rect = RectF(x, y, x + w, y + h)
                )
            )
        }

        val picked = nonMaxSuppression(detections)
        return picked.map {
            Detection(
                className = variant.outputLabels.getOrElse(it.classId) { "UNKNOWN" },
                score = it.score,
                box = it.rect
            )
        }
    }

    private fun createSessionWithFallback(modelPath: String): Pair<OrtSession, String> {
        DebugLogManager.addLog("模型加载", "尝试使用 NNAPI/GPU 执行提供者")
        try {
            val nnapiOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(cpuThreads)
                setInterOpNumThreads(1)
                runCatching { setOptimizedModelFilePath(optimizedModelPath) }
                addNnapi()
            }
            val nnapiSession = environment.createSession(modelPath, nnapiOptions)
            return nnapiSession to "NNAPI/GPU"
        } catch (e: Exception) {
            DebugLogManager.addLog("模型加载", "NNAPI/GPU 初始化失败，回退 CPU: ${e.message}")
        }

        val cpuOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(cpuThreads)
            setInterOpNumThreads(1)
            runCatching { setOptimizedModelFilePath(optimizedModelPath) }
        }
        val cpuSession = environment.createSession(modelPath, cpuOptions)
        return cpuSession to "CPU"
    }

    private fun ensureModelFiles(context: Context): File {
        val modelFile = File(context.cacheDir, modelConfig.modelFileName)
        if (!modelFile.exists()) {
            context.assets.open(modelConfig.modelFileName).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val dataAsset = modelConfig.dataFileName
        if (!dataAsset.isNullOrBlank()) {
            val dataFile = File(context.cacheDir, dataAsset)
            if (!dataFile.exists()) {
                context.assets.open(dataAsset).use { input ->
                    dataFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return modelFile
    }

    private fun getFaceLandmarkRunner(): FaceLandmarkOnnxRunner? {
        if (faceLandmarkInitAttempted) return faceLandmarkRunner
        synchronized(this) {
            if (faceLandmarkInitAttempted) return faceLandmarkRunner
            faceLandmarkRunner = runCatching {
                FaceLandmarkModelProvider.getRunner(appContext)
            }.onFailure { e ->
                DebugLogManager.addLog("模型加载", "人脸关键点模型初始化失败，回退裁剪眼部: ${e.message}")
            }.getOrNull()
            faceLandmarkInitAttempted = true
        }
        return faceLandmarkRunner
    }

    private fun attachFaceLandmarks(bitmap: Bitmap, detections: List<Detection>): List<Detection> {
        val runner = getFaceLandmarkRunner() ?: return detections
        val faceLandmarks = runCatching {
            runner.run(bitmap)
        }.onFailure { e ->
            DebugLogManager.addLog("模型检测", "人脸关键点推理失败，回退裁剪眼部: ${e.message}")
        }.getOrNull().orEmpty()
        if (faceLandmarks.isEmpty()) return detections

        val usedLandmarkIndices = mutableSetOf<Int>()
        var matchedCount = 0
        val enriched = detections.map { detection ->
            if (!DetectionConfig.supportsFaceLandmarks(detection.className)) return@map detection
            var bestIndex = -1
            var bestIou = 0f
            faceLandmarks.forEachIndexed { index, face ->
                if (usedLandmarkIndices.contains(index)) return@forEachIndexed
                val overlap = iou(detection.box, face.box)
                if (overlap > bestIou) {
                    bestIou = overlap
                    bestIndex = index
                }
            }

            if (bestIndex >= 0 && bestIou >= 0.05f) {
                usedLandmarkIndices.add(bestIndex)
                matchedCount++
                val face = faceLandmarks[bestIndex]
                detection.copy(
                    leftEye = face.leftEye,
                    rightEye = face.rightEye,
                    eyeBar = face.eyeBar
                )
            } else {
                detection
            }
        }
        if (matchedCount > 0) {
            DebugLogManager.addLog("模型检测", "人脸关键点匹配成功: $matchedCount/${detections.count { DetectionConfig.supportsFaceLandmarks(it.className) }}")
        }
        return enriched
    }

    private fun nonMaxSuppression(candidates: List<DetectionCandidate>): List<DetectionCandidate> {
        val sorted = candidates.sortedByDescending { it.score }
        val selected = mutableListOf<DetectionCandidate>()

        for (candidate in sorted) {
            var shouldSelect = true
            for (existing in selected) {
                val iou = iou(candidate.rect, existing.rect)
                if (iou > DetectionConfig.NMS_THRESHOLD) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) selected.add(candidate)
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

        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    data class Detection(
        val className: String,  // 改为className以匹配Python版本的"class"
        val score: Float,
        val box: RectF,
        val leftEye: PointF? = null,
        val rightEye: PointF? = null,
        val eyeBar: RectF? = null
    )

    private data class DetectionCandidate(
        val score: Float,
        val classId: Int,
        val rect: RectF
    )

    private data class PreprocessMeta(
        val originalWidth: Int,
        val originalHeight: Int,
        val squareSize: Int
    )

    data class ModelConfig(
        val modelFileName: String,
        val dataFileName: String?,
        val inputSize: Int,
        val optimizedFileName: String,
        val label: String
    )


}
