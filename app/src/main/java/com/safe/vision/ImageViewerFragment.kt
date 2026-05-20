package com.safe.vision

import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.media.MediaPlayer
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ImageViewerFragment : Fragment() {
    private lateinit var fullSizeImage: ImageView
    private lateinit var fullSizeVideo: VideoView
    private lateinit var imageInfo: TextView
    private lateinit var emptyText: TextView
    private lateinit var animeMetadataBadge: TextView
    private lateinit var randomCountdownView: CircularCountdownView
    private lateinit var detectionEditorOverlay: DetectionEditorOverlayView
    private lateinit var editToolbar: LinearLayout
    private lateinit var btnAddBox: MaterialButton
    private lateinit var btnDoneEdit: MaterialButton
    private lateinit var videoSeekContainer: LinearLayout
    private lateinit var videoSeekSlider: Slider
    private lateinit var videoPositionText: TextView
    private lateinit var videoDurationText: TextView

    private var allMedia = listOf<File>()
    private var currentIndex = 0
    private lateinit var privacyProcessor: ImagePrivacyProcessor
    private var currentProcessedBitmap: Bitmap? = null
    private var currentMetadataFile: File? = null
    private var pendingTargetPath: String? = null
    private val browseHistory = ArrayDeque<Int>()
    private lateinit var appSettings: AppSettingsManager
    private var randomPlayJob: Job? = null
    private var metronomeJob: Job? = null
    private var metronomePlayer: MediaPlayer? = null
    private lateinit var scaleDetector: ScaleGestureDetector
    private val imageMatrix = android.graphics.Matrix()
    private val matrixValues = FloatArray(9)
    private var minScale = 1f
    private var maxScale = 4f
    private var currentScale = 1f
    private var isScaling = false
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var touchSlop = 0
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var pendingLongPress: Runnable? = null
    private var longPressTriggered = false
    private var currentVideoFile: File? = null
    private var lastPlaybackErrorPath: String? = null
    private var videoTouchStartX = 0f
    private var videoTouchStartY = 0f
    private var videoSwipeHandled = false
    private var videoProgressJob: Job? = null
    private var isSeekingVideo = false
    private var randomCandidateIndices: List<Int> = emptyList()
    private var randomQueueCacheKey: String? = null
    private var randomQueueBuildJob: Job? = null
    private var randomQueueEmptyToastKey: String? = null
    private val metadataLabelCache = mutableMapOf<String, MetadataLabelCacheEntry>()
    private var isEditMode = false
    private var editableDetections: MutableList<EditableDetection> = mutableListOf()
    private var editingMetadataFile: File? = null

    private data class MetadataLabelCacheEntry(
        val lastModified: Long,
        val labels: Set<String>
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_image_viewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fullSizeImage = view.findViewById(R.id.fullSizeImage)
        fullSizeVideo = view.findViewById(R.id.fullSizeVideo)
        imageInfo = view.findViewById(R.id.imageInfo)
        emptyText = view.findViewById(R.id.emptyText)
        animeMetadataBadge = view.findViewById(R.id.animeMetadataBadge)
        randomCountdownView = view.findViewById(R.id.randomCountdownView)
        detectionEditorOverlay = view.findViewById(R.id.detectionEditorOverlay)
        editToolbar = view.findViewById(R.id.editToolbar)
        btnAddBox = view.findViewById(R.id.btnAddBox)
        btnDoneEdit = view.findViewById(R.id.btnDoneEdit)
        videoSeekContainer = view.findViewById(R.id.videoSeekContainer)
        videoSeekSlider = view.findViewById(R.id.videoSeekSlider)
        videoPositionText = view.findViewById(R.id.videoPositionText)
        videoDurationText = view.findViewById(R.id.videoDurationText)
        
        // 初始化隐私处理器
        privacyProcessor = ImagePrivacyProcessor(requireContext())
        appSettings = AppSettingsManager.getInstance(requireContext())
        fullSizeVideo.setMediaController(null)
        fullSizeVideo.setOnErrorListener { _, what, extra ->
            val failedFile = currentVideoFile
            if (failedFile == null) {
                DebugLogManager.addLog("媒体浏览", "视频播放失败(无目标文件): what=$what extra=$extra")
                return@setOnErrorListener true
            }
            if (!failedFile.exists()) {
                DebugLogManager.addLog("媒体浏览", "视频已删除，跳过播放错误提示: ${failedFile.name}")
                loadMedia()
                return@setOnErrorListener true
            }
            if (lastPlaybackErrorPath != failedFile.absolutePath) {
                Toast.makeText(requireContext(), R.string.viewer_video_playback_failed, Toast.LENGTH_SHORT).show()
                lastPlaybackErrorPath = failedFile.absolutePath
            }
            DebugLogManager.addLog("媒体浏览", "视频播放失败: ${failedFile.name}, what=$what extra=$extra")
            true
        }
        btnAddBox.setOnClickListener { showAddLabelDialog() }
        btnDoneEdit.setOnClickListener { saveAndExitEditMode() }
        detectionEditorOverlay.onBoxLongPress = { id -> showEditBoxActions(id) }
        detectionEditorOverlay.onDataChanged = { Unit }
        detectionEditorOverlay.eyeModeResolver = { detection -> DetectionConfig.isEyeRegionLabel(detection.label) }
        videoSeekSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            updateVideoProgressTexts(value.roundToInt(), currentVideoDurationMs())
        }
        videoSeekSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isSeekingVideo = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val targetPosition = slider.value.roundToInt()
                if (currentVideoFile != null && fullSizeVideo.visibility == View.VISIBLE) {
                    fullSizeVideo.seekTo(targetPosition)
                    updateVideoProgressTexts(targetPosition, currentVideoDurationMs())
                }
                isSeekingVideo = false
            }
        })

        fullSizeVideo.setOnTouchListener { _, event ->
            if (isEditMode) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    videoTouchStartX = event.x
                    videoTouchStartY = event.y
                    videoSwipeHandled = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - videoTouchStartX
                    val dy = event.y - videoTouchStartY
                    val swipeThreshold = touchSlop * 3
                    val isHorizontalSwipe = abs(dx) >= swipeThreshold && abs(dx) > abs(dy)
                    if (isHorizontalSwipe && !videoSwipeHandled) {
                        videoSwipeHandled = true
                        if (dx > 0f) {
                            showPreviousMedia()
                        } else {
                            showNextMedia()
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val consumed = videoSwipeHandled
                    videoSwipeHandled = false
                    consumed
                }
                else -> false
            }
        }
        touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isScaling = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (fullSizeImage.drawable == null) return false
                    val scaleFactor = detector.scaleFactor
                    val targetScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)
                    val deltaScale = targetScale / currentScale
                    if (abs(deltaScale - 1f) < 0.0005f) return true
                    imageMatrix.postScale(
                        deltaScale,
                        deltaScale,
                        detector.focusX,
                        detector.focusY
                    )
                    currentScale = targetScale
                    constrainImageMatrix()
                    fullSizeImage.imageMatrix = imageMatrix
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScaling = false
                }
            }
        )

        fullSizeImage.setOnTouchListener { _, event ->
            if (isEditMode) return@setOnTouchListener true
            if (fullSizeImage.drawable == null) {
                return@setOnTouchListener false
            }
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    scheduleLongPress(fullSizeImage)
                    activePointerId = event.getPointerId(0)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    downTouchX = event.x
                    downTouchY = event.y
                    swipeStartX = event.x
                    swipeStartY = event.y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downTouchX) > touchSlop || abs(event.y - downTouchY) > touchSlop) {
                        cancelLongPress(fullSizeImage)
                    }
                    if (!isScaling && currentScale > minScale) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            val dx = x - lastTouchX
                            val dy = y - lastTouchY
                            if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                                isDragging = true
                                imageMatrix.postTranslate(dx, dy)
                                constrainImageMatrix()
                                fullSizeImage.imageMatrix = imageMatrix
                            }
                            lastTouchX = x
                            lastTouchY = y
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    cancelLongPress(fullSizeImage)
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newIndex = if (pointerIndex == 0) 1 else 0
                        if (newIndex < event.pointerCount) {
                            activePointerId = event.getPointerId(newIndex)
                            lastTouchX = event.getX(newIndex)
                            lastTouchY = event.getY(newIndex)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(fullSizeImage)
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (longPressTriggered) {
                        isDragging = false
                        isScaling = false
                        return@setOnTouchListener true
                    }
                    val deltaX = event.x - swipeStartX
                    val deltaY = event.y - swipeStartY
                    val swipeThreshold = touchSlop * 3
                    val isHorizontalSwipe = !isScaling &&
                        !isDragging &&
                        currentScale <= minScale + 0.0005f &&
                        abs(deltaX) >= swipeThreshold &&
                        abs(deltaX) > abs(deltaY)
                    if (isHorizontalSwipe) {
                        if (deltaX > 0f) {
                            showPreviousMedia()
                        } else {
                            showNextMedia()
                        }
                    }
                    val isTap = !isScaling &&
                        !isDragging &&
                        currentScale <= minScale + 0.0005f &&
                        abs(event.x - downTouchX) <= touchSlop &&
                        abs(event.y - downTouchY) <= touchSlop
                    if (!isHorizontalSwipe && isTap) {
                        val width = fullSizeImage.width.takeIf { it > 0 } ?: 0
                        if (width > 0) {
                            when {
                                event.x < width / 3f -> showPreviousMedia()
                                event.x > width * 2f / 3f -> showNextMedia()
                                else -> Unit
                            }
                        }
                    }
                    val consumed = isScaling || isDragging || event.pointerCount > 1 || isTap || isHorizontalSwipe
                    isDragging = false
                    isScaling = false
                    return@setOnTouchListener consumed
                }
            }
            true
        }

        val mediaContainer = view.findViewById<View>(R.id.mediaContainer)
        mediaContainer?.setOnTouchListener { touchedView, event ->
            if (isEditMode) return@setOnTouchListener true
            // 图片场景下由 fullSizeImage 独占处理点击翻页，避免容器与图片双通道重复触发
            if (fullSizeImage.visibility == View.VISIBLE) {
                return@setOnTouchListener false
            }
            if (event.pointerCount > 1 || isScaling) {
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    scheduleLongPress(touchedView)
                    swipeStartX = event.x
                    swipeStartY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - swipeStartX) > touchSlop || abs(event.y - swipeStartY) > touchSlop) {
                        cancelLongPress(touchedView)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress(touchedView)
                    if (longPressTriggered) {
                        return@setOnTouchListener true
                    }
                    val width = touchedView.width.takeIf { it > 0 } ?: return@setOnTouchListener true
                    val x = event.x
                    val deltaX = x - swipeStartX
                    val deltaY = event.y - swipeStartY
                    val swipeThreshold = touchSlop * 3
                    val isHorizontalSwipe = abs(deltaX) >= swipeThreshold && abs(deltaX) > abs(deltaY)
                    if (isHorizontalSwipe) {
                        if (deltaX > 0f) {
                            showPreviousMedia()
                        } else {
                            showNextMedia()
                        }
                    } else {
                        when {
                            x < width / 3f -> showPreviousMedia()
                            x > width * 2f / 3f -> showNextMedia()
                            else -> Unit
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(touchedView)
                }
            }
            true
        }
        parentFragmentManager.setFragmentResultListener(
            "open_media_request",
            viewLifecycleOwner
        ) { _, bundle ->
            if (isEditMode) {
                Toast.makeText(requireContext(), R.string.viewer_edit_tip_finish_first, Toast.LENGTH_SHORT).show()
                return@setFragmentResultListener
            }
            bundle.getString("path")?.let {
                pendingTargetPath = it
                tryShowPendingMedia()
            }
        }

        loadMedia()
    }

    private fun loadMedia() {
        val currentPath = allMedia.getOrNull(currentIndex)?.absolutePath
        val rootDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val safeNetDir = File(rootDir, FolderModels.SAFE_NET_DIR)
        val noDetectionDir = File(rootDir, FolderModels.NO_DETECTION_DIR)
        val videoDir = File(rootDir, FolderModels.SAFE_VIDEO_DIR)
        val customImageDirs = appSettings.getCustomImageFolders().map { File(rootDir, it) }

        val safeNetImages = listMediaFiles(safeNetDir, video = false)
        val noDetectionImages = listMediaFiles(noDetectionDir, video = false)
        val customFolderImages = customImageDirs.flatMap { dir -> listMediaFiles(dir, video = false) }
        val videos = listMediaFiles(videoDir, video = true)

        // 合并并按修改时间排序（最新的在前）
        allMedia = (safeNetImages + customFolderImages + noDetectionImages + videos)
            .sortedByDescending { it.lastModified() }
        browseHistory.clear()
        rebuildRandomCandidatesIfNeeded(force = true)

        if (allMedia.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            fullSizeImage.visibility = View.GONE
            fullSizeVideo.visibility = View.GONE
            imageInfo.visibility = View.GONE
            animeMetadataBadge.visibility = View.GONE
            hideVideoSeekControls()
            currentProcessedBitmap = null
            currentMetadataFile = null
            cancelRandomPlay()
            randomCountdownView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            fullSizeImage.visibility = View.VISIBLE
            imageInfo.visibility = View.VISIBLE
            if (!tryShowPendingMedia()) {
                val fallbackIndex = currentPath?.let { path ->
                    allMedia.indexOfFirst { it.absolutePath == path }
                } ?: -1
                if (fallbackIndex >= 0) {
                    showMedia(fallbackIndex)
                } else {
                    showMedia(0)
                }
            }
            restartRandomPlayCountdown()
        }
    }

    private fun listMediaFiles(dir: File, video: Boolean): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file ->
            if (!file.isFile) return@listFiles false
            val ext = file.extension.lowercase()
            if (video) ext in listOf("mp4", "mov", "mkv") else ext in listOf("jpg", "jpeg", "png", "webp")
        }?.toList() ?: emptyList()
    }

    private fun showMedia(index: Int) {
        if (isEditMode) {
            exitEditMode()
        }
        if (allMedia.isEmpty()) return

        // 确保索引在有效范围内
        currentIndex = if (index >= allMedia.size) 0 else if (index < 0) allMedia.size - 1 else index

        val mediaFile = allMedia[currentIndex]
        val isVideo = isVideoFile(mediaFile)
        currentMetadataFile = null

        if (isVideo) {
            currentProcessedBitmap = null
            animeMetadataBadge.visibility = View.GONE
            fullSizeVideo.stopPlayback()
            stopVideoProgressUpdates()
            fullSizeImage.visibility = View.GONE
            fullSizeVideo.visibility = View.VISIBLE
            currentVideoFile = mediaFile
            if (!mediaFile.exists()) {
                DebugLogManager.addLog("媒体浏览", "视频文件不存在，刷新列表: ${mediaFile.name}")
                loadMedia()
                return
            }
            try {
                fullSizeVideo.setVideoURI(Uri.fromFile(mediaFile))
                fullSizeVideo.setMediaController(null)
                fullSizeVideo.setOnPreparedListener {
                    it.isLooping = false
                    lastPlaybackErrorPath = null
                    configureVideoSeekControls(fullSizeVideo.duration)
                    startVideoProgressUpdates()
                    if (shouldAutoPlayVideo()) {
                        fullSizeVideo.start()
                    }
                }
                fullSizeVideo.setOnCompletionListener {
                    stopVideoProgressUpdates()
                    syncVideoProgress(forceDuration = currentVideoDurationMs())
                }
                DebugLogManager.addLog("媒体浏览", "播放视频: ${mediaFile.name}")
            } catch (e: Exception) {
                hideVideoSeekControls()
                DebugLogManager.addLog("媒体浏览", "视频播放失败: ${e.message}")
            }
        } else {
            currentVideoFile = null
            fullSizeVideo.stopPlayback()
            fullSizeVideo.visibility = View.GONE
            stopVideoProgressUpdates()
            hideVideoSeekControls()
            fullSizeImage.visibility = View.VISIBLE
            // 加载图片
            try {
                val bitmap = BitmapFactory.decodeFile(mediaFile.absolutePath)
                if (bitmap == null) {
                    fullSizeImage.setImageResource(android.R.drawable.ic_menu_report_image)
                    DebugLogManager.addLog("媒体浏览", "加载图片失败: ${mediaFile.name}")
                    return
                }

                // 查找对应的元数据文件
                val metadataFile = findMetadataFile(mediaFile)
                currentMetadataFile = metadataFile
                updateAnimeMetadataBadge(metadataFile)
                
                // 应用隐私遮挡
                val processedBitmap = privacyProcessor.applyPrivacyBlur(bitmap, metadataFile)
                currentProcessedBitmap = processedBitmap

                // 先重置矩阵再设置位图，避免新图先按旧缩放状态绘制一帧导致“闪一下”
                val zoomApplied = resetImageZoom(processedBitmap)
                fullSizeImage.setImageBitmap(processedBitmap)
                if (!zoomApplied) {
                    fullSizeImage.post { resetImageZoom(processedBitmap) }
                }
                detectionEditorOverlay.setImageMatrix(imageMatrix)
                
                DebugLogManager.addLog("媒体浏览", "显示图片: ${mediaFile.name}")
                if (metadataFile != null) {
                    DebugLogManager.addLog("媒体浏览", "应用隐私遮挡，使用元数据: ${metadataFile.name}")
                }
            } catch (e: Exception) {
                fullSizeImage.setImageResource(android.R.drawable.ic_menu_report_image)
                animeMetadataBadge.visibility = View.GONE
                DebugLogManager.addLog("媒体浏览", "加载图片失败: ${e.message}")
            }
        }

        // 更新信息文本
        imageInfo.text = getString(R.string.viewer_image_info, currentIndex + 1, allMedia.size)
        restartRandomPlayCountdown()
    }

    private fun updateAnimeMetadataBadge(metadataFile: File?) {
        val isAnimeMetadata = runCatching {
            if (metadataFile == null || !metadataFile.exists()) return@runCatching false
            DetectionMetadataFormat.parse(metadataFile.readText(Charsets.UTF_8)).labelProfile ==
                DetectionConfig.LabelProfile.ANIME
        }.getOrElse { error ->
            DebugLogManager.addLog("媒体浏览", "读取动漫元数据标记失败: ${metadataFile?.name}, ${error.message}")
            false
        }
        animeMetadataBadge.visibility = if (isAnimeMetadata) View.VISIBLE else View.GONE
    }

    private fun resetImageZoom(bitmap: Bitmap): Boolean {
        fullSizeImage.scaleType = ImageView.ScaleType.MATRIX
        val viewWidth = fullSizeImage.width.toFloat()
        val viewHeight = fullSizeImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return false
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()
        val baseScale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val dx = (viewWidth - imageWidth * baseScale) * 0.5f
        val dy = (viewHeight - imageHeight * baseScale) * 0.5f
        imageMatrix.reset()
        imageMatrix.postScale(baseScale, baseScale)
        imageMatrix.postTranslate(dx, dy)
        fullSizeImage.imageMatrix = imageMatrix
        minScale = baseScale
        currentScale = baseScale
        maxScale = baseScale * 4f
        return true
    }

    private fun constrainImageMatrix() {
        val drawable = fullSizeImage.drawable ?: return
        val viewWidth = fullSizeImage.width.toFloat()
        val viewHeight = fullSizeImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        imageMatrix.getValues(matrixValues)
        val scaleX = matrixValues[android.graphics.Matrix.MSCALE_X]
        val scaleY = matrixValues[android.graphics.Matrix.MSCALE_Y]
        val transX = matrixValues[android.graphics.Matrix.MTRANS_X]
        val transY = matrixValues[android.graphics.Matrix.MTRANS_Y]
        val scaledWidth = imageWidth * scaleX
        val scaledHeight = imageHeight * scaleY
        val minTransX = if (scaledWidth <= viewWidth) {
            (viewWidth - scaledWidth) * 0.5f
        } else {
            viewWidth - scaledWidth
        }
        val maxTransX = if (scaledWidth <= viewWidth) {
            (viewWidth - scaledWidth) * 0.5f
        } else {
            0f
        }
        val minTransY = if (scaledHeight <= viewHeight) {
            (viewHeight - scaledHeight) * 0.5f
        } else {
            viewHeight - scaledHeight
        }
        val maxTransY = if (scaledHeight <= viewHeight) {
            (viewHeight - scaledHeight) * 0.5f
        } else {
            0f
        }
        var dx = 0f
        var dy = 0f
        if (transX < minTransX) dx = minTransX - transX
        if (transX > maxTransX) dx = maxTransX - transX
        if (transY < minTransY) dy = minTransY - transY
        if (transY > maxTransY) dy = maxTransY - transY
        if (dx != 0f || dy != 0f) {
            imageMatrix.postTranslate(dx, dy)
        }
    }
    
    /**
     * 查找图片对应的元数据文件
     */
    private fun findMetadataFile(imageFile: File): File? {
        val parentDir = imageFile.parentFile ?: return null
        return parentDir.listFiles()?.firstOrNull { file ->
            file.isFile &&
                file.nameWithoutExtension.equals(imageFile.nameWithoutExtension, ignoreCase = true) &&
                file.extension.equals("json", ignoreCase = true)
        }
    }

    private fun showNextMedia() {
        if (isEditMode) return
        if (allMedia.isEmpty()) return
        if (appSettings.isRandomBrowseEnabled()) {
            if (allMedia.size <= 1) return
            rebuildRandomCandidatesIfNeeded()
            browseHistory.offerLast(currentIndex)
            val candidates = randomCandidateIndices.filter { index ->
                index != currentIndex && index in allMedia.indices
            }
            if (candidates.isEmpty()) {
                if (randomQueueBuildJob?.isActive == true) return
                showRandomQueueEmptyToastOnce()
                return
            }
            showMedia(candidates.random())
            return
        }
        showMedia(currentIndex + 1)
    }

    private fun showPreviousMedia() {
        if (isEditMode) return
        if (allMedia.isEmpty()) return
        if (appSettings.isRandomBrowseEnabled()) {
            val previousIndex = browseHistory.pollLast() ?: return
            showMedia(previousIndex)
            return
        }
        showMedia(currentIndex - 1)
    }

    private fun shareCurrentMedia() {
        val mediaFile = allMedia.getOrNull(currentIndex)
        if (mediaFile == null) {
            Toast.makeText(requireContext(), R.string.viewer_share_no_media, Toast.LENGTH_SHORT).show()
            return
        }
        shareMedia(mediaFile)
    }

    private fun shareMedia(mediaFile: File) {
        if (isVideoFile(mediaFile)) {
            shareVideo(mediaFile)
        } else {
            shareImage(mediaFile)
        }
    }

    private fun showMediaActionsPanel() {
        val mediaFile = allMedia.getOrNull(currentIndex)
        if (mediaFile == null) return
        val contentView = layoutInflater.inflate(R.layout.dialog_viewer_actions, null)
        val editButton = contentView.findViewById<MaterialButton>(R.id.actionEditButton)
        val deleteButton = contentView.findViewById<MaterialButton>(R.id.actionDeleteButton)
        val exportButton = contentView.findViewById<MaterialButton>(R.id.actionExportButton)
        val shareButton = contentView.findViewById<MaterialButton>(R.id.actionShareButton)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(contentView)
        editButton.setOnClickListener {
            bottomSheetDialog.dismiss()
            if (isVideoFile(mediaFile)) {
                Toast.makeText(requireContext(), R.string.viewer_edit_no_image, Toast.LENGTH_SHORT).show()
            } else {
                enterEditMode()
            }
        }
        deleteButton.setOnClickListener {
            bottomSheetDialog.dismiss()
            confirmDeleteCurrentMedia(mediaFile)
        }
        exportButton.setOnClickListener {
            bottomSheetDialog.dismiss()
            exportCurrentMedia(mediaFile)
        }
        shareButton.setOnClickListener {
            bottomSheetDialog.dismiss()
            shareMedia(mediaFile)
        }
        bottomSheetDialog.show()
    }

    private fun scheduleLongPress(target: View) {
        if (isEditMode) return
        cancelLongPress(target)
        pendingLongPress = Runnable {
            if (isEditMode) return@Runnable
            longPressTriggered = true
            target.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showMediaActionsPanel()
        }
        target.postDelayed(pendingLongPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress(target: View) {
        pendingLongPress?.let { target.removeCallbacks(it) }
        pendingLongPress = null
    }

    private fun confirmDeleteCurrentMedia(mediaFile: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.viewer_delete_title)
            .setMessage(getString(R.string.viewer_delete_message, mediaFile.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.viewer_action_delete) { _, _ ->
                deleteCurrentMedia(mediaFile)
            }
            .show()
    }

    private fun deleteCurrentMedia(mediaFile: File) {
        val imageMetadata = if (!isVideoFile(mediaFile)) findMetadataFile(mediaFile) else null
        val mediaDeleted = mediaFile.delete()
        val metadataDeleted = imageMetadata?.delete() ?: true
        if (mediaDeleted && metadataDeleted) {
            DebugLogManager.addLog("媒体浏览", "删除媒体成功: ${mediaFile.name}")
            Toast.makeText(
                requireContext(),
                getString(R.string.viewer_delete_success, mediaFile.name),
                Toast.LENGTH_SHORT
            ).show()
            loadMedia()
        } else {
            DebugLogManager.addLog("媒体浏览", "删除媒体失败: ${mediaFile.name}")
            Toast.makeText(
                requireContext(),
                getString(R.string.viewer_delete_failed, mediaFile.name),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportCurrentMedia(mediaFile: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                if (isVideoFile(mediaFile)) {
                    exportVideoToGallery(mediaFile)
                } else {
                    exportImageToGallery(mediaFile)
                }
            }
            if (success) {
                Toast.makeText(requireContext(), R.string.viewer_export_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.viewer_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportImageToGallery(imageFile: File): Boolean {
        var insertedUri: Uri? = null
        return try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
            val metadataFile = findMetadataFile(imageFile)
            val processedBitmap = privacyProcessor.applyPrivacyBlur(bitmap, metadataFile)
            val mimeType = when (imageFile.extension.lowercase(Locale.getDefault())) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val suffix = when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val displayName = "${imageFile.nameWithoutExtension}_exported.$suffix"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SafeVision")
                } else {
                    val targetDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "SafeVision"
                    ).apply {
                        if (!exists()) mkdirs()
                    }
                    put(MediaStore.MediaColumns.DATA, File(targetDir, displayName).absolutePath)
                }
            }
            val resolver = requireContext().applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            insertedUri = uri
            resolver.openOutputStream(uri)?.use { output ->
                val format = when (mimeType) {
                    "image/png" -> Bitmap.CompressFormat.PNG
                    "image/webp" -> Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                }
                if (!processedBitmap.compress(format, 95, output)) {
                    throw IllegalStateException("压缩失败")
                }
            } ?: throw IllegalStateException("无法写入输出流")
            DebugLogManager.addLog("媒体浏览", "导出图片成功: ${imageFile.name}")
            true
        } catch (e: Exception) {
            insertedUri?.let { requireContext().applicationContext.contentResolver.delete(it, null, null) }
            DebugLogManager.addLog("媒体浏览", "导出图片失败: ${e.message}")
            false
        }
    }

    private fun exportVideoToGallery(videoFile: File): Boolean {
        var insertedUri: Uri? = null
        return try {
            val extension = videoFile.extension.lowercase(Locale.getDefault()).ifBlank { "mp4" }
            val mimeType = when (extension) {
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "3gp" -> "video/3gpp"
                else -> "video/mp4"
            }
            val displayName = "${videoFile.nameWithoutExtension}_exported.$extension"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SafeVision")
                } else {
                    val targetDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "SafeVision"
                    ).apply {
                        if (!exists()) mkdirs()
                    }
                    put(MediaStore.MediaColumns.DATA, File(targetDir, displayName).absolutePath)
                }
            }
            val resolver = requireContext().applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            insertedUri = uri
            resolver.openOutputStream(uri)?.use { output ->
                videoFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法写入输出流")
            DebugLogManager.addLog("媒体浏览", "导出视频成功: ${videoFile.name}")
            true
        } catch (e: Exception) {
            insertedUri?.let { requireContext().applicationContext.contentResolver.delete(it, null, null) }
            DebugLogManager.addLog("媒体浏览", "导出视频失败: ${e.message}")
            false
        }
    }

    private fun shareVideo(mediaFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                mediaFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, getString(R.string.viewer_share_title)))
            DebugLogManager.addLog("媒体浏览", "分享视频: ${mediaFile.name}")
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.viewer_share_error, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
            DebugLogManager.addLog("媒体浏览", "分享视频失败: ${e.message}")
        }
    }

    private fun shareImage(mediaFile: File) {
        val processedBitmap = currentProcessedBitmap ?: run {
            val bitmap = BitmapFactory.decodeFile(mediaFile.absolutePath)
            if (bitmap == null) {
                Toast.makeText(requireContext(), R.string.viewer_share_error_generic, Toast.LENGTH_SHORT).show()
                DebugLogManager.addLog("媒体浏览", "分享失败: 图片解码失败 ${mediaFile.name}")
                return
            }
            val metadataFile = currentMetadataFile ?: findMetadataFile(mediaFile)
            currentMetadataFile = metadataFile
            privacyProcessor.applyPrivacyBlur(bitmap, metadataFile).also {
                currentProcessedBitmap = it
            }
        }

        try {
            val cacheDir = requireContext().externalCacheDir ?: requireContext().cacheDir
            val shareDir = File(cacheDir, "shared_images")
            if (!shareDir.exists() && !shareDir.mkdirs()) {
                throw IllegalStateException("无法创建分享目录")
            }

            val extension = when (mediaFile.extension.lowercase()) {
                "png" -> "png"
                "webp" -> "webp"
                else -> "jpg"
            }
            val shareFile = File(
                shareDir,
                "${mediaFile.nameWithoutExtension}_shared.$extension"
            )

            FileOutputStream(shareFile).use { output ->
                val format = when (extension) {
                    "png" -> Bitmap.CompressFormat.PNG
                    "webp" -> Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                }
                if (!processedBitmap.compress(format, 95, output)) {
                    throw IllegalStateException("图片压缩失败")
                }
            }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                shareFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, getString(R.string.viewer_share_title)))
            DebugLogManager.addLog("媒体浏览", "分享图片: ${shareFile.name}")
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.viewer_share_error, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
            DebugLogManager.addLog("媒体浏览", "分享失败: ${e.message}")
        }
    }

    private fun isVideoFile(file: File): Boolean {
        return file.extension.lowercase() in listOf("mp4", "mov", "mkv")
    }

    // 公共方法，用于从外部跳转到指定媒体
    fun showImage(imageFile: File) { // 保持旧方法名以兼容调用
        pendingTargetPath = imageFile.absolutePath
        tryShowPendingMedia()
    }

    override fun onResume() {
        super.onResume()
        // 每次页面可见时重新加载媒体，以便显示最新的处理结果
        loadMedia()
        startMetronomeIfNeeded()
    }

    private fun tryShowPendingMedia(): Boolean {
        val targetPath = pendingTargetPath ?: return false
        val index = allMedia.indexOfFirst { it.absolutePath == targetPath }
        return if (index >= 0) {
            showMedia(index)
            pendingTargetPath = null
            true
        } else {
            false
        }
    }

    private fun restartRandomPlayCountdown() {
        cancelRandomPlay()
        if (!appSettings.isRandomPlayEnabled()) return
        val intervalSeconds = appSettings.getRandomPlayIntervalSeconds().coerceAtLeast(3)
        if (allMedia.none { !isVideoFile(it) }) return
        randomPlayJob = viewLifecycleOwner.lifecycleScope.launch {
            var remaining = intervalSeconds
            val total = intervalSeconds
            randomCountdownView.visibility = View.VISIBLE
            randomCountdownView.setCountdown(total, remaining)
            while (remaining > 0) {
                randomCountdownView.setCountdown(total, remaining)
                delay(1000L)
                remaining--
            }
            randomCountdownView.visibility = View.GONE
            switchToRandomImage()
        }
    }

    private fun cancelRandomPlay() {
        randomPlayJob?.cancel()
        randomPlayJob = null
        randomCountdownView.visibility = View.GONE
    }

    private fun switchToRandomImage() {
        if (allMedia.isEmpty()) return
        rebuildRandomCandidatesIfNeeded()
        val images = randomCandidateIndices.filter { index ->
            index in allMedia.indices && !isVideoFile(allMedia[index])
        }
        if (images.isEmpty()) {
            if (randomQueueBuildJob?.isActive == true) return
            showRandomQueueEmptyToastOnce()
            return
        }
        val target = images.random()
        // 如果随机到当前图片且有多张，则再抽一次
        val targetIndex = if (images.size > 1 && target == currentIndex) {
            images.filter { it != currentIndex }.random()
        } else {
            target
        }
        showMedia(targetIndex)
        DebugLogManager.addLog("媒体浏览", "随机播放切换到: ${allMedia[targetIndex].name}")
    }

    private fun rebuildRandomCandidatesIfNeeded(force: Boolean = false) {
        if (allMedia.isEmpty()) {
            randomQueueBuildJob?.cancel()
            randomCandidateIndices = emptyList()
            randomQueueCacheKey = null
            randomQueueEmptyToastKey = null
            return
        }
        val allowedTypes = appSettings.getRandomQueueTypes()
        val selectedLabels = appSettings.getRandomQueueLabels()
        val cacheKey = buildRandomQueueCacheKey(allowedTypes, selectedLabels)
        if (!force && randomQueueCacheKey == cacheKey) return

        randomQueueCacheKey = cacheKey
        randomCandidateIndices = emptyList()
        randomQueueBuildJob?.cancel()
        randomQueueBuildJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val candidates = buildRandomCandidateIndices(allowedTypes, selectedLabels)
            withContext(Dispatchers.Main) {
                if (!isAdded || randomQueueCacheKey != cacheKey) return@withContext
                randomCandidateIndices = candidates
                randomQueueEmptyToastKey = null
                DebugLogManager.addLog("媒体浏览", "随机候选列表已刷新: ${candidates.size} 项")
            }
        }
    }

    private fun buildRandomQueueCacheKey(
        allowedTypes: Set<AppSettingsManager.RandomQueueType>,
        selectedLabels: Set<String>
    ): String {
        val typePart = allowedTypes.map { it.prefValue }.sorted().joinToString(",")
        val labelPart = selectedLabels.sorted().joinToString(",")
        val mediaPart = allMedia.joinToString("|") { "${it.absolutePath}:${it.lastModified()}" }
        return "$typePart#$labelPart#$mediaPart"
    }

    private fun buildRandomCandidateIndices(
        allowedTypes: Set<AppSettingsManager.RandomQueueType>,
        selectedLabels: Set<String>
    ): List<Int> {
        val allLabelsSelected = selectedLabels.size >= DetectionConfig.LABELS.size
        val result = ArrayList<Int>(allMedia.size)
        allMedia.forEachIndexed { index, file ->
            if (!isAllowedByRandomSource(file, allowedTypes)) return@forEachIndexed
            if (allLabelsSelected) {
                result.add(index)
                return@forEachIndexed
            }
            if (isVideoFile(file)) {
                return@forEachIndexed
            }
            val labels = extractLabelsForImage(file) ?: return@forEachIndexed
            if (labels.any { selectedLabels.contains(it) }) {
                result.add(index)
            }
        }
        return result
    }

    private fun isAllowedByRandomSource(
        file: File,
        allowedTypes: Set<AppSettingsManager.RandomQueueType>
    ): Boolean {
        val parentName = file.parentFile?.name?.lowercase(Locale.getDefault())
        return when (parentName) {
            "safenet" -> allowedTypes.contains(AppSettingsManager.RandomQueueType.SAFENET)
            "no_detection" -> allowedTypes.contains(AppSettingsManager.RandomQueueType.NO_DETECTION)
            "safevideo" -> allowedTypes.contains(AppSettingsManager.RandomQueueType.VIDEO_OUTPUT)
            else -> {
                // 自建图片文件夹与 SafeNet 同属图片输出来源，按同一开关过滤
                if (isVideoFile(file)) {
                    allowedTypes.contains(AppSettingsManager.RandomQueueType.VIDEO_OUTPUT)
                } else {
                    allowedTypes.contains(AppSettingsManager.RandomQueueType.SAFENET)
                }
            }
        }
    }

    private fun extractLabelsForImage(imageFile: File): Set<String>? {
        val metadataFile = findMetadataFile(imageFile) ?: return null
        val cacheKey = metadataFile.absolutePath
        val modified = metadataFile.lastModified()
        metadataLabelCache[cacheKey]?.let { cached ->
            if (cached.lastModified == modified) {
                return cached.labels
            }
        }
        return try {
            val labels = parseMetadataLabels(metadataFile)
            metadataLabelCache[cacheKey] = MetadataLabelCacheEntry(modified, labels)
            labels
        } catch (e: Exception) {
            DebugLogManager.addLog("媒体浏览", "随机队列读取JSON失败: ${metadataFile.name}, ${e.message}")
            null
        }
    }

    private fun parseMetadataLabels(metadataFile: File): Set<String> {
        val array = DetectionMetadataFormat.parse(metadataFile.readText(Charsets.UTF_8)).detections
        val labels = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val label = obj.optString("class")
            if (DetectionConfig.LABELS.contains(label)) {
                labels.add(label)
            }
        }
        return labels
    }

    private fun showRandomQueueEmptyToastOnce() {
        val cacheKey = randomQueueCacheKey ?: return
        if (randomQueueEmptyToastKey == cacheKey) return
        randomQueueEmptyToastKey = cacheKey
        Toast.makeText(requireContext(), R.string.viewer_random_queue_no_candidates, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        cancelRandomPlay()
        stopMetronome()
        randomQueueBuildJob?.cancel()
        randomQueueBuildJob = null
        fullSizeVideo.pause()
        stopVideoProgressUpdates()
        hideVideoSeekControls()
    }

    private fun shouldAutoPlayVideo(): Boolean {
        return lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        stopVideoProgressUpdates()
        super.onDestroyView()
    }

    private fun configureVideoSeekControls(durationMs: Int) {
        val safeDuration = durationMs.coerceAtLeast(0)
        videoSeekSlider.valueFrom = 0f
        videoSeekSlider.valueTo = max(safeDuration, 1).toFloat()
        videoSeekSlider.value = 0f
        updateVideoProgressTexts(0, safeDuration)
        videoSeekContainer.visibility = View.VISIBLE
    }

    private fun hideVideoSeekControls() {
        isSeekingVideo = false
        videoSeekContainer.visibility = View.GONE
        videoSeekSlider.valueFrom = 0f
        videoSeekSlider.valueTo = 1f
        videoSeekSlider.value = 0f
        updateVideoProgressTexts(0, 0)
    }

    private fun startVideoProgressUpdates() {
        stopVideoProgressUpdates()
        if (currentVideoFile == null || fullSizeVideo.visibility != View.VISIBLE) return
        videoProgressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && currentVideoFile != null && fullSizeVideo.visibility == View.VISIBLE) {
                if (!isSeekingVideo) {
                    syncVideoProgress()
                }
                delay(250L)
            }
        }
    }

    private fun stopVideoProgressUpdates() {
        videoProgressJob?.cancel()
        videoProgressJob = null
    }

    private fun syncVideoProgress(forceDuration: Int? = null) {
        val duration = (forceDuration ?: currentVideoDurationMs()).coerceAtLeast(0)
        val position = fullSizeVideo.currentPosition.coerceIn(0, duration)
        val sliderMax = max(duration, 1).toFloat()
        if (videoSeekSlider.valueTo != sliderMax) {
            videoSeekSlider.valueTo = sliderMax
        }
        if (!isSeekingVideo) {
            videoSeekSlider.value = position.toFloat()
        }
        updateVideoProgressTexts(position, duration)
    }

    private fun currentVideoDurationMs(): Int {
        return runCatching { fullSizeVideo.duration }.getOrDefault(0).coerceAtLeast(0)
    }

    private fun updateVideoProgressTexts(positionMs: Int, durationMs: Int) {
        videoPositionText.text = formatVideoTime(positionMs)
        videoDurationText.text = formatVideoTime(durationMs)
    }

    private fun formatVideoTime(totalMs: Int): String {
        val totalSeconds = (totalMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    private fun enterEditMode() {
        val mediaFile = allMedia.getOrNull(currentIndex) ?: return
        if (isVideoFile(mediaFile)) {
            Toast.makeText(requireContext(), R.string.viewer_edit_no_image, Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = currentProcessedBitmap ?: BitmapFactory.decodeFile(mediaFile.absolutePath)
        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.viewer_edit_no_image, Toast.LENGTH_SHORT).show()
            return
        }
        val metadataFile = currentMetadataFile ?: findMetadataFile(mediaFile)
        currentMetadataFile = metadataFile
        editingMetadataFile = metadataFile ?: File(mediaFile.parentFile, "${mediaFile.nameWithoutExtension}.json")
        editableDetections = DetectionMetadataIo.read(metadataFile)
        isEditMode = true
        cancelRandomPlay()
        editToolbar.visibility = View.VISIBLE
        detectionEditorOverlay.visibility = View.VISIBLE
        detectionEditorOverlay.setEditorData(
            editableDetections,
            imageMatrix,
            bitmap.width,
            bitmap.height
        )
    }

    private fun exitEditMode() {
        isEditMode = false
        editToolbar.visibility = View.GONE
        detectionEditorOverlay.visibility = View.GONE
        editableDetections = mutableListOf()
        editingMetadataFile = null
    }

    private fun showAddLabelDialog() {
        if (!isEditMode) return
        val labels = DetectionConfig.LABELS
        val names = labels.map { DetectionConfig.getDisplayName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.viewer_edit_label_picker_title)
            .setItems(names) { _, which ->
                val label = labels[which]
                addNewEditableDetection(label)
            }
            .show()
    }

    private fun addNewEditableDetection(label: String) {
        val mediaFile = allMedia.getOrNull(currentIndex) ?: return
        val bitmap = currentProcessedBitmap ?: BitmapFactory.decodeFile(mediaFile.absolutePath) ?: return
        val center = imageCenterRect(bitmap.width.toFloat(), bitmap.height.toFloat())
        val size = min(center.width(), center.height()) * 0.22f
        val left = (center.centerX() - size / 2f).coerceAtLeast(0f)
        val top = (center.centerY() - size / 2f).coerceAtLeast(0f)
        val rect = RectF(
            left,
            top,
            (left + size).coerceAtMost(bitmap.width.toFloat()),
            (top + size).coerceAtMost(bitmap.height.toFloat())
        )
        editableDetections.add(
            EditableDetection(
                label = label,
                rect = rect,
                score = 1f
            )
        )
        detectionEditorOverlay.setEditorData(editableDetections, imageMatrix, bitmap.width, bitmap.height)
    }

    private fun imageCenterRect(bitmapW: Float, bitmapH: Float): RectF {
        val inv = android.graphics.Matrix()
        imageMatrix.invert(inv)
        val pts = floatArrayOf(0f, 0f, fullSizeImage.width.toFloat(), fullSizeImage.height.toFloat())
        inv.mapPoints(pts, 0, pts, 0, 1)
        inv.mapPoints(pts, 2, pts, 2, 1)
        val left = pts[0].coerceIn(0f, bitmapW)
        val top = pts[1].coerceIn(0f, bitmapH)
        val right = pts[2].coerceIn(0f, bitmapW)
        val bottom = pts[3].coerceIn(0f, bitmapH)
        return RectF(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
    }

    private fun showEditBoxActions(id: String) {
        if (!isEditMode) return
        val item = editableDetections.firstOrNull { it.id == id }
        val eyeModeEnabled = item?.let { isEyeModeEnabledForLabel(it.label) } == true
        val actions = buildList {
            add(getString(R.string.viewer_edit_action_resize))
            add(getString(R.string.viewer_edit_action_rotate))
            if (eyeModeEnabled) {
                add(getString(R.string.viewer_edit_action_edit_eye_bar))
            }
            add(getString(R.string.viewer_edit_action_delete))
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    getString(R.string.viewer_edit_action_resize) -> {
                        detectionEditorOverlay.enableResizeMode(id)
                    }
                    getString(R.string.viewer_edit_action_rotate) -> {
                        showBoxRotationDialog(id)
                    }
                    getString(R.string.viewer_edit_action_edit_eye_bar) -> {
                        detectionEditorOverlay.enableEyeBarEditMode(id)
                    }
                    else -> {
                        detectionEditorOverlay.removeById(id)
                    }
                }
            }
            .show()
    }

    private fun showBoxRotationDialog(id: String) {
        val item = editableDetections.firstOrNull { it.id == id } ?: return
        val slider = Slider(requireContext()).apply {
            valueFrom = -180f
            valueTo = 180f
            stepSize = 1f
            value = item.boxRotationDegrees ?: 0f
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.viewer_edit_rotate_title)
            .setView(slider)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                detectionEditorOverlay.updateBoxRotation(id, slider.value)
            }
            .show()
    }

    private fun isEyeModeEnabledForLabel(label: String): Boolean {
        return DetectionConfig.isEyeRegionLabel(label)
    }

    private fun saveAndExitEditMode() {
        if (!isEditMode) return
        val target = editingMetadataFile ?: return
        try {
            DetectionMetadataIo.write(target, editableDetections)
            currentMetadataFile = target
            Toast.makeText(requireContext(), R.string.viewer_edit_saved, Toast.LENGTH_SHORT).show()
            exitEditMode()
            showMedia(currentIndex)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.viewer_edit_save_failed, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startMetronomeIfNeeded() {
        if (!appSettings.isMetronomeEnabled()) {
            stopMetronome()
            return
        }
        if (metronomeJob != null) return
        val intervalSeconds = appSettings.getMetronomeIntervalSeconds()
        if (intervalSeconds <= 0f) return

        val player = metronomePlayer ?: createMetronomePlayer() ?: return
        metronomePlayer = player
        val intervalMs = (intervalSeconds * 1000f).toLong().coerceAtLeast(50L)
        metronomeJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    if (player.isPlaying) {
                        player.seekTo(0)
                    } else {
                        player.seekTo(0)
                        player.start()
                    }
                } catch (e: Exception) {
                    DebugLogManager.addLog("节拍器", "播放失败: ${e.message}")
                }
                delay(intervalMs)
            }
        }
        DebugLogManager.addLog("节拍器", "节拍器启动，间隔 ${intervalSeconds}s")
    }

    private fun createMetronomePlayer(): MediaPlayer? {
        return try {
            val assetFd = requireContext().assets.openFd("beat.mp3")
            MediaPlayer().apply {
                setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
                isLooping = false
                setVolume(1f, 1f)
                prepare()
            }.also {
                assetFd.close()
            }
        } catch (e: Exception) {
            DebugLogManager.addLog("节拍器", "加载节拍音频失败: ${e.message}")
            null
        }
    }

    private fun stopMetronome() {
        metronomeJob?.cancel()
        metronomeJob = null
        metronomePlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                DebugLogManager.addLog("节拍器", "停止播放失败: ${e.message}")
            }
            player.release()
        }
        metronomePlayer = null
    }
}
