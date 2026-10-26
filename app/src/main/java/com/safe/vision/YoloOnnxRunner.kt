package com.safe.vision

import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight helper responsible for loading the YOLO TFLite model, preprocessing input bitmaps,
 * and returning structured detection metadata similar to the reference Python script.
 */
class YoloOnnxRunner(
    context: Context,
    private val variant: DetectionModelVariant = DetectionModelVariant.STANDARD
) : Closeable {

    private val appContext = context.applicationContext
    private val modelConfig = ModelConfig(
        modelFileName = variant.modelFileName,
        dataFileName = variant.dataFileName,
        inputSize = variant.inputSize,
        optimizedFileName = variant.optimizedFileName,
        label = variant.runtimeLabel
    )
    private val modelInputSize = modelConfig.inputSize
    private var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate?
    private var executionProvider: String
    private val inputArea = modelInputSize * modelInputSize
    private val resolvedModelFiles = ensureModelFiles(context)
    private val optimizedModelPath: String =
        resolvedModelFiles.optimizedModelFile.absolutePath
    private val cpuThreads = DetectionConfig.defaultCpuThreadCount()
    private val runLock = Any()
    private val inferenceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "yolo-tflite-${variant.name}")
    }
    private var closed = false

    private fun <T> onInferenceThread(block: () -> T): T {
        val future = inferenceExecutor.submit<T> { block() }
        // Keep caller-owned bitmaps alive until inference has finished, including on interruption.
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (error: ExecutionException) {
                    throw (error.cause ?: error)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    private var resizedBitmap: Bitmap? = null
    private var resizedCanvas: Canvas? = null
    private val resizeDstRect = Rect(0, 0, modelInputSize, modelInputSize)
    private val resizeLetterboxDstRect = RectF()
    @Volatile
    private var faceLandmarkRunner: FaceLandmarkOnnxRunner? = null
    @Volatile
    private var faceLandmarkInitAttempted = false

    init {
        val modelFile = resolvedModelFiles.modelFile
        val (createdInterpreter, delegate) = try {
            onInferenceThread {
                val created = createInterpreter(modelFile)
                try {
                    val input = created.first.getInputTensor(0)
                    YoloTensorInput.requireShape(input.shape(), modelInputSize)
                    require(input.dataType() == DataType.FLOAT32) {
                        "不支持的 TFLite 输入类型: ${input.dataType()}，期望 FLOAT32"
                    }
                    DebugLogManager.addLog(
                        "模型加载",
                        "输入=${input.shape().contentToString()} FLOAT32 NHWC RGB [0,1], " +
                            "输出=${created.first.getOutputTensor(0).shape().contentToString()}"
                    )
                    created
                } catch (error: Throwable) {
                    runCatching { created.first.close() }
                    runCatching { created.second?.close() }
                    throw error
                }
            }
        } catch (error: Throwable) {
            inferenceExecutor.shutdown()
            throw error
        }
        interpreter = createdInterpreter
        gpuDelegate = delegate
        executionProvider = if (delegate != null) "TFLite GPU (FP16)" else "TFLite CPU"
        DebugLogManager.addLog("模型加载", "推理后端: $executionProvider (模型: ${modelConfig.label})")
    }

    fun run(bitmap: Bitmap, enrichFaceLandmarks: Boolean = true): List<Detection> {
        return synchronized(runLock) {
            check(!closed) { "YOLO runner is closed" }
            onInferenceThread { runInternal(bitmap, enrichFaceLandmarks) }
        }
    }

    private fun runInternal(bitmap: Bitmap, enrichFaceLandmarks: Boolean): List<Detection> {
        val startTime = System.currentTimeMillis()
        val (inputTensor, meta) = preprocess(bitmap)

        val inferenceStart = System.currentTimeMillis()
        val output = runInferenceWithCpuFallback(inputTensor)
        val inferenceTime = System.currentTimeMillis() - inferenceStart
        val processedData = normalizeOutput(output)
        var detections = postProcess(processedData, meta)
        if (enrichFaceLandmarks && detections.any { DetectionConfig.supportsFaceLandmarks(it.className) }) {
            detections = attachFaceLandmarks(bitmap, detections)
        }
        detections = withDerivedEyeRegionDetections(
            detections,
            surfaceWidth = bitmap.width,
            surfaceHeight = bitmap.height
        )
        val totalTime = System.currentTimeMillis() - startTime
        DebugLogManager.addLog(
            "模型检测",
            "检测完成: 后端=$executionProvider, 输入=${bitmap.width}x${bitmap.height}, 推理=${inferenceTime}ms, 总耗时=${totalTime}ms, 结果=${detections.size}"
        )

        return detections
    }

    private fun runInferenceWithCpuFallback(inputTensor: ByteBuffer): Array<FloatArray> {
        try {
            return runInference(inputTensor)
        } catch (error: Throwable) {
            if (gpuDelegate == null || !isRecoverableGpuFailure(error)) throw error
            DebugLogManager.addLog("模型检测", "GPU 推理失败，回退 CPU: ${error.javaClass.simpleName}: ${error.message}")
            // Interpreter and delegate must also be released on their owning thread.
            runCatching { interpreter.close() }
            runCatching { gpuDelegate?.close() }
            gpuDelegate = null
            try {
                interpreter = createInterpreter(resolvedModelFiles.modelFile, allowGpu = false).first
                executionProvider = "TFLite CPU"
            } catch (cpuError: Throwable) {
                cpuError.addSuppressed(error)
                // The old interpreter has been released; do not allow it to be used again.
                closed = true
                runCatching { faceLandmarkRunner?.close() }
                resizedBitmap?.recycle()
                resizedBitmap = null
                resizedCanvas = null
                inferenceExecutor.shutdown()
                throw cpuError
            }
            try {
                // Retry once with fresh output storage and the original input.
                inputTensor.rewind()
                return runInference(inputTensor)
            } catch (cpuError: Throwable) {
                cpuError.addSuppressed(error)
                throw cpuError
            }
        }
    }

    private fun runInference(inputTensor: ByteBuffer): Array<FloatArray> {
        val outputShape = interpreter.getOutputTensor(0).shape()
        return when (outputShape.size) {
            3 -> {
                require(outputShape[0] == 1) {
                    "不支持的 TFLite 输出 batch: ${outputShape.contentToString()}"
                }
                // TFLite requires the destination to retain the tensor's batch dimension.
                val batchedOutput = Array(outputShape[0]) {
                    Array(outputShape[1]) { FloatArray(outputShape[2]) }
                }
                interpreter.run(inputTensor, batchedOutput)
                batchedOutput[0]
            }
            2 -> {
                val unbatchedOutput = Array(outputShape[0]) { FloatArray(outputShape[1]) }
                interpreter.run(inputTensor, unbatchedOutput)
                unbatchedOutput
            }
            else -> error("不支持的 TFLite 输出形状: ${outputShape.contentToString()}")
        }
    }
    
    // 移除私有调试日志方法，直接使用DebugLogManager

    private fun preprocess(bitmap: Bitmap): Pair<ByteBuffer, YoloImageGeometry> {
        val sourceBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        try {
            val width = sourceBitmap.width
            val height = sourceBitmap.height
            val squareSize = max(width, height)

            val pixels = IntArray(inputArea)

            if (resizedBitmap == null) {
                resizedBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
                resizedCanvas = Canvas(resizedBitmap!!)
            }
            resizedBitmap?.eraseColor(Color.BLACK)
            val scale = modelInputSize.toFloat() / squareSize.toFloat()
            resizeLetterboxDstRect.set(
                0f,
                0f,
                width * scale,
                height * scale
            )
            resizedCanvas?.drawBitmap(sourceBitmap, null, resizeLetterboxDstRect, null)

            resizedBitmap?.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)

            // TFLite uses [1, height, width, 3]: RGB must be adjacent for each pixel.
            val floatBuffer = YoloTensorInput.fromArgb(pixels)

            val meta = YoloImageGeometry(
                originalWidth = width,
                originalHeight = height
            )
            return floatBuffer to meta
        } finally {
            if (sourceBitmap !== bitmap && !sourceBitmap.isRecycled) {
                sourceBitmap.recycle()
            }
        }
    }

    private fun postProcess(raw: Array<FloatArray>, meta: YoloImageGeometry): List<Detection> {
        if (raw.isEmpty()) return emptyList()
        return if (isEndToEndDetections(raw)) {
            postProcessEndToEnd(raw, meta)
        } else {
            postProcessLegacy(raw, meta)
        }
    }

    private fun isEndToEndDetections(raw: Array<FloatArray>): Boolean {
        return raw.firstOrNull()?.size == 6
    }

    private fun postProcessLegacy(raw: Array<FloatArray>, meta: YoloImageGeometry): List<Detection> {
        val detections = mutableListOf<DetectionCandidate>()
        val attrs = raw.size
        val rows = raw[0].size

        for (i in 0 until rows) {
            if (attrs < 5) continue
            var maxScore = Float.NEGATIVE_INFINITY
            var classId = -1
            for (j in 4 until attrs) {
                val score = raw[j][i]
                if (score.isFinite() && score >= 0f && score > maxScore) {
                    maxScore = score
                    classId = j - 4
                }
            }
            if (classId == -1) continue
            if (maxScore < DetectionConfig.SCORE_THRESHOLD) continue
            val x = raw[0][i]
            val y = raw[1][i]
            val w = raw[2][i]
            val h = raw[3][i]
            val box = meta.restoreBox(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
                ?: continue

            detections.add(
                DetectionCandidate(
                    score = maxScore,
                    classId = classId,
                    rect = RectF(box[0], box[1], box[2], box[3])
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

    private fun postProcessEndToEnd(raw: Array<FloatArray>, meta: YoloImageGeometry): List<Detection> {
        val detections = mutableListOf<Detection>()
        raw.forEach { prediction ->
            if (prediction.size < 6) return@forEach

            val score = prediction[4]
            if (!score.isFinite() || score < DetectionConfig.SCORE_THRESHOLD) return@forEach

            val classId = prediction[5].toInt()
            if (classId !in variant.outputLabels.indices) return@forEach

            val box = meta.restoreBox(prediction[0], prediction[1], prediction[2], prediction[3])
                ?: return@forEach

            detections.add(
                Detection(
                    className = variant.outputLabels[classId],
                    score = score,
                    box = RectF(box[0], box[1], box[2], box[3])
                )
            )
        }
        return deduplicateHighOverlapEndToEndDetections(detections)
    }

    private fun isRecoverableGpuFailure(error: Throwable): Boolean =
        error is Exception || error is LinkageError

    private fun createInterpreter(
        modelFile: File,
        allowGpu: Boolean = true
    ): Pair<Interpreter, GpuDelegate?> {
        val mapped = java.io.FileInputStream(modelFile).use { stream ->
            stream.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
        }
        var delegate: GpuDelegate? = null
        try {
            if (allowGpu && CompatibilityList().use { it.isDelegateSupportedOnThisDevice }) {
                delegate = GpuDelegate()
                return Interpreter(mapped, Interpreter.Options().addDelegate(delegate)) to delegate
            }
        } catch (error: Throwable) {
            runCatching { delegate?.close() }
            if (!isRecoverableGpuFailure(error)) throw error
            DebugLogManager.addLog("模型加载", "GPU 初始化失败，回退 CPU: ${error.javaClass.simpleName}: ${error.message}")
        }
        return Interpreter(mapped, Interpreter.Options().setNumThreads(cpuThreads)) to null
    }

    private fun normalizeOutput(raw: Array<FloatArray>): Array<FloatArray> {
        if (raw.isEmpty()) return raw
        if (raw.size == 6) return Array(raw[0].size) { i -> FloatArray(6) { j -> raw[j][i] } }
        return raw
    }

    private fun ensureModelFiles(context: Context): ResolvedModelFiles {
        val cacheRoot = File(context.cacheDir, "onnx_models").apply { mkdirs() }
        val modelDir = File(cacheRoot, modelConfig.label).apply { mkdirs() }
        val modelFile = File(modelDir, modelConfig.modelFileName)
        copyAssetIfNeeded(context, modelConfig.modelFileName, modelFile)
        val dataAsset = modelConfig.dataFileName
        if (!dataAsset.isNullOrBlank()) {
            copyAssetIfNeeded(context, dataAsset, File(modelDir, dataAsset))
        }
        cleanupStaleModelCaches(cacheRoot, modelDir.name)
        cleanupLegacyFlatCacheFiles(context.cacheDir)
        return ResolvedModelFiles(
            modelFile = modelFile,
            optimizedModelFile = File(modelDir, modelConfig.optimizedFileName)
        )
    }

    private fun copyAssetIfNeeded(context: Context, assetName: String, targetFile: File) {
        if (targetFile.exists()) return
        targetFile.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun cleanupStaleModelCaches(cacheRoot: File, activeDirName: String) {
        val prefix = "${modelConfig.label}_"
        cacheRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name == activeDirName || !dir.name.startsWith(prefix)) return@forEach
            runCatching { dir.deleteRecursively() }
                .onFailure { e ->
                    DebugLogManager.addLog(
                        "模型加载",
                        "清理旧模型缓存失败: ${dir.name} - ${e.message}",
                        DebugLogManager.LogLevel.WARN
                    )
                }
        }
    }

    private fun cleanupLegacyFlatCacheFiles(cacheDir: File) {
        val legacyNames = buildList {
            add(modelConfig.modelFileName)
            add(modelConfig.optimizedFileName)
            modelConfig.dataFileName?.takeIf { it.isNotBlank() }?.let(::add)
        }
        legacyNames.forEach { name ->
            val file = File(cacheDir, name)
            if (!file.exists()) return@forEach
            runCatching { file.delete() }
        }
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

    private fun deduplicateHighOverlapEndToEndDetections(detections: List<Detection>): List<Detection> {
        if (detections.size < 2) return detections
        val selected = mutableListOf<Detection>()

        for (candidate in detections.sortedByDescending { it.score }) {
            var shouldSelect = true
            for (existing in selected) {
                if (candidate.className != existing.className) continue
                if (iou(candidate.box, existing.box) > DetectionConfig.END_TO_END_DUPLICATE_IOU_THRESHOLD) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) selected.add(candidate)
        }

        if (selected.size != detections.size) {
            DebugLogManager.addLog(
                "模型检测",
                "端到端结果高重合去重: ${detections.size} -> ${selected.size}"
            )
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
        val eyeBar: RectF? = null,
        val isDerivedEyeRegion: Boolean = false
    )

    private data class DetectionCandidate(
        val score: Float,
        val classId: Int,
        val rect: RectF
    )

    data class ModelConfig(
        val modelFileName: String,
        val dataFileName: String?,
        val inputSize: Int,
        val optimizedFileName: String,
        val label: String
    )

    private data class ResolvedModelFiles(
        val modelFile: File,
        val optimizedModelFile: File
    )

    override fun close() {
        synchronized(runLock) {
            if (closed) return
            closed = true
            try {
                onInferenceThread {
                    runCatching { faceLandmarkRunner?.close() }
                    runCatching { interpreter.close() }
                    runCatching { gpuDelegate?.close() }
                    resizedBitmap?.recycle()
                    resizedBitmap = null
                    resizedCanvas = null
                }
            } finally {
                inferenceExecutor.shutdown()
            }
        }
    }

    companion object {
        fun withDerivedEyeRegionDetections(
            detections: List<Detection>,
            surfaceWidth: Int? = null,
            surfaceHeight: Int? = null
        ): List<Detection> {
            if (detections.isEmpty()) return detections
            if (detections.any { DetectionConfig.isEyeRegionLabel(it.className) }) return detections
            val derived = mutableListOf<Detection>()
            detections.forEach { detection ->
                derived.add(detection)
                if (!DetectionConfig.canDeriveEyeRegion(detection.className)) return@forEach
                val eyeRect = EyeRegionHelper.deriveEyeRegion(
                    EyeRegionHelper.EyeRegionSource(
                        sourceLabel = detection.className,
                        faceRect = detection.box,
                        eyes = detection.leftEye?.let { left ->
                            detection.rightEye?.let { right -> left to right }
                        },
                        eyeBar = detection.eyeBar
                    ),
                    surfaceWidth = surfaceWidth ?: detection.box.right.toInt().coerceAtLeast(1),
                    surfaceHeight = surfaceHeight ?: detection.box.bottom.toInt().coerceAtLeast(1)
                ) ?: return@forEach
                derived.add(
                    detection.copy(
                        className = DetectionConfig.EYE_REGION_LABEL,
                        box = eyeRect,
                        eyeBar = eyeRect,
                        isDerivedEyeRegion = true
                    )
                )
            }
            return derived
        }
    }


}
