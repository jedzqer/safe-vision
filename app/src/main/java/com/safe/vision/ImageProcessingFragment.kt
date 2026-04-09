package com.safe.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import com.safe.vision.MediaSaveHelper.SaveResult
import kotlin.coroutines.resume

class ImageProcessingFragment : Fragment() {
    private enum class ProgressMode {
        IDLE,
        SINGLE_IMAGE,
        BATCH_IMAGE,
        SINGLE_VIDEO,
        BATCH_VIDEO
    }

    private var yoloRunner: YoloOnnxRunner? = null
    private lateinit var imagePreview: ImageView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var selectButton: Button
    private lateinit var selectVideoButton: Button
    private lateinit var selectFolderButton: Button
    private lateinit var runButton: Button
    private lateinit var screenDetectionButton: Button
    private lateinit var animeModelSwitch: Switch
    private lateinit var videoHighLoadSwitch: Switch
    private lateinit var batchResultsRecyclerView: RecyclerView
    
    private var selectedBitmap: Bitmap? = null
    private var selectedBytes: ByteArray? = null
    private var selectedName: String? = null
    private var isModelLoaded = false
    private var loadedModelVariant: DetectionModelVariant? = null
    private var lastSelectionUsedFilePicker = false
    private var lastVideoSelectionUsedFilePicker = false
    private var progressMode: ProgressMode = ProgressMode.IDLE
    private var currentBatchVideoIndex: Int = 0
    private var totalBatchVideos: Int = 0
    private var completedBatchVideos: Int = 0
    private var pendingScreenCaptureAfterAccessibilityPermission = false
    private var pendingScreenDetectionAfterNotificationPermission = false
    
    private lateinit var batchManager: BatchProcessingManager
    private lateinit var batchResultsAdapter: BatchResultsAdapter
    private lateinit var videoProcessingManager: VideoProcessingManager
    private lateinit var privacySettingsManager: PrivacySettingsManager
    private lateinit var appSettingsManager: AppSettingsManager
    private lateinit var mediaSelectionHelper: MediaSelectionHelper
    private lateinit var folderMediaScanner: FolderMediaScanner

    private val pickImagesFromGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        handleImagesSelected(uris)
    }

    private val pickImagesFromFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        handleImagesSelected(uris)
    }

    private val pickVideoFromGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            handleVideoSelected(uri)
        } else {
            Toast.makeText(requireContext(), R.string.image_processing_no_video_selected, Toast.LENGTH_SHORT).show()
        }
    }

    private val pickVideoFromFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            handleVideosSelected(uris)
        } else {
            Toast.makeText(requireContext(), R.string.image_processing_no_video_selected, Toast.LENGTH_SHORT).show()
        }
    }

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            handleFolderSelected(uri)
        } else {
            Toast.makeText(requireContext(), R.string.image_processing_no_folder_selected, Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            ScreenDetectionStateHolder.setRunning(getString(R.string.screen_detection_status_starting))
            ContextCompat.startForegroundService(
                requireContext(),
                ScreenDetectionService.createStartIntent(requireContext(), result.resultCode, data)
            )
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.screen_detection_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
            updateScreenDetectionUi(ScreenDetectionStateHolder.state.value)
        }
    }

    private val accessibilityPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!pendingScreenCaptureAfterAccessibilityPermission) return@registerForActivityResult
        pendingScreenCaptureAfterAccessibilityPermission = false
        if (ScreenAccessibilityOverlayService.isEnabled(requireContext())) {
            requestScreenCapturePermission()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.screen_detection_accessibility_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!pendingScreenDetectionAfterNotificationPermission) return@registerForActivityResult
        pendingScreenDetectionAfterNotificationPermission = false
        if (granted) {
            ensureAccessibilityPermissionThenRequestScreenCapture()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.screen_detection_notification_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.activity_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imagePreview = view.findViewById(R.id.imagePreview)
        statusText = view.findViewById(R.id.txtStatus)
        progressBar = view.findViewById(R.id.progressBar)
        selectButton = view.findViewById(R.id.btnSelectImage)
        selectVideoButton = view.findViewById(R.id.btnSelectVideo)
        selectFolderButton = view.findViewById(R.id.btnSelectFolder)
        runButton = view.findViewById(R.id.btnRunModel)
        screenDetectionButton = view.findViewById(R.id.btnScreenDetection)
        animeModelSwitch = view.findViewById(R.id.switchAnimeModel)
        videoHighLoadSwitch = view.findViewById(R.id.switchVideoHighLoad)
        batchResultsRecyclerView = view.findViewById(R.id.batchResultsRecyclerView)

        // 隐藏调试相关的UI元素
        view.findViewById<View>(R.id.debugToggle).visibility = View.GONE
        view.findViewById<View>(R.id.debugContainer).visibility = View.GONE

        runButton.isEnabled = false
        
        // 初始化批量处理管理器
        batchManager = BatchProcessingManager.getInstance(requireContext())
        videoProcessingManager = VideoProcessingManager.getInstance(requireContext())
        privacySettingsManager = PrivacySettingsManager.getInstance(requireContext())
        appSettingsManager = AppSettingsManager.getInstance(requireContext())
        animeModelSwitch.isChecked = appSettingsManager.getDetectionModelVariant() == DetectionModelVariant.ANIME
        animeModelSwitch.setOnCheckedChangeListener { _, isChecked ->
            val newVariant = if (isChecked) DetectionModelVariant.ANIME else DetectionModelVariant.STANDARD
            if (appSettingsManager.getDetectionModelVariant() == newVariant) return@setOnCheckedChangeListener
            appSettingsManager.setDetectionModelVariant(newVariant)
            yoloRunner = null
            isModelLoaded = false
            loadedModelVariant = null
            Toast.makeText(
                requireContext(),
                getString(R.string.image_processing_model_switched, newVariant.displayName),
                Toast.LENGTH_SHORT
            ).show()
            DebugLogManager.addLog("模型", "首页检测模型切换为: ${newVariant.runtimeLabel}")
        }
        videoHighLoadSwitch.isChecked = appSettingsManager.isVideoHighLoadEnabled()
        videoHighLoadSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (appSettingsManager.isVideoHighLoadEnabled() == isChecked) return@setOnCheckedChangeListener
            appSettingsManager.setVideoHighLoadEnabled(isChecked)
            Toast.makeText(
                requireContext(),
                getString(if (isChecked) R.string.video_high_load_enabled else R.string.video_high_load_disabled),
                Toast.LENGTH_SHORT
            ).show()
            DebugLogManager.addLog("视频处理", "首页高负载模式切换为: ${if (isChecked) "开启" else "关闭"}")
        }
        mediaSelectionHelper = MediaSelectionHelper(requireContext().contentResolver)
        folderMediaScanner = FolderMediaScanner(requireContext().contentResolver)
        
        // 设置批量结果列表
        batchResultsAdapter = BatchResultsAdapter()
        batchResultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        batchResultsRecyclerView.adapter = batchResultsAdapter
        showSinglePreview()
        progressMode = ProgressMode.IDLE
        statusText.text = getString(R.string.status_placeholder)
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.isIndeterminate = false

        selectButton.setOnClickListener {
            launchImagePicker()
        }

        selectVideoButton.setOnClickListener {
            launchVideoPicker()
        }

        selectFolderButton.setOnClickListener {
            launchFolderPicker()
        }

        runButton.setOnClickListener {
            processSelection()
        }

        screenDetectionButton.setOnClickListener {
            toggleScreenDetection()
        }
        
        // 监听批量处理状态
        lifecycleScope.launch {
            batchManager.processingState.collect { state ->
                updateBatchUI(state)
            }
        }
        
        // 监听批量处理进度
        lifecycleScope.launch {
            batchManager.progress.collect { progress ->
                updateBatchProgress(progress)
            }
        }

        // 监听视频处理状态
        lifecycleScope.launch {
            videoProcessingManager.state.collect { state ->
                updateVideoState(state)
            }
        }

        // 监听视频处理进度
        lifecycleScope.launch {
            videoProcessingManager.progress.collect { progress ->
                updateVideoProgress(progress)
            }
        }
        
        // 监听批量处理结果
        lifecycleScope.launch {
            batchManager.results.collect { results ->
                batchResultsAdapter.updateResults(results)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ScreenDetectionStateHolder.state.collect { state ->
                updateScreenDetectionUi(state)
            }
        }

        updateScreenDetectionUi(ScreenDetectionStateHolder.state.value)
        
        DebugLogManager.addLog("图片处理", "图片处理页面已初始化")
    }

    private fun toggleScreenDetection() {
        if (ScreenDetectionStateHolder.state.value.isRunning) {
            requireContext().startService(ScreenDetectionService.createStopIntent(requireContext()))
            return
        }
        showScreenDetectionSettingsDialog()
    }

    private fun showScreenDetectionSettingsDialog() {
        val density = resources.displayMetrics.density
        val appSettings = appSettingsManager
        val contentView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val animeSwitch = SwitchCompat(requireContext()).apply {
            text = getString(R.string.screen_detection_settings_anime_model)
            isChecked = appSettings.isScreenDetectionAnimeModelEnabled()
            setTextColor(DialogUtils.resolveThemeColor(context, R.attr.svColorTextPrimary))
        }
        val intervalTitle = TextView(requireContext()).apply {
            text = getString(R.string.screen_detection_settings_interval)
            setTextColor(DialogUtils.resolveThemeColor(context, R.attr.svColorTextPrimary))
            textSize = 15f
        }
        val intervalValue = TextView(requireContext()).apply {
            setTextColor(DialogUtils.resolveThemeColor(context, R.attr.svColorTextSecondary))
            textSize = 13f
        }
        val intervalSeekBar = SeekBar(requireContext())
        val initialInterval = appSettings.getScreenDetectionIntervalSeconds()
        intervalSeekBar.max = 99
        intervalSeekBar.progress = ((initialInterval * 100f).toInt() - 1).coerceIn(0, intervalSeekBar.max)
        fun currentIntervalSeconds(): Float = ((intervalSeekBar.progress + 1) / 100f)
        fun updateIntervalSummary() {
            intervalValue.text = getString(
                R.string.screen_detection_settings_interval_value,
                currentIntervalSeconds().toDouble()
            )
        }
        updateIntervalSummary()
        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateIntervalSummary()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        contentView.addView(
            animeSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        contentView.addView(
            intervalTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * density).toInt()
            }
        )
        contentView.addView(
            intervalValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            }
        )
        contentView.addView(
            intervalSeekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        DialogUtils.ensureDialogLayoutParams(contentView)
        DialogUtils.builder(requireContext())
            .setTitle(R.string.screen_detection_settings_title)
            .setView(contentView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                appSettings.setScreenDetectionAnimeModelEnabled(animeSwitch.isChecked)
                appSettings.setScreenDetectionIntervalSeconds(currentIntervalSeconds())
                ensureNotificationPermissionThenStartScreenDetection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ensureNotificationPermissionThenStartScreenDetection() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            ensureAccessibilityPermissionThenRequestScreenCapture()
            return
        }
        val context = requireContext()
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        ) {
            ensureAccessibilityPermissionThenRequestScreenCapture()
            return
        }
        pendingScreenDetectionAfterNotificationPermission = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun ensureAccessibilityPermissionThenRequestScreenCapture() {
        if (ScreenAccessibilityOverlayService.isEnabled(requireContext())) {
            requestScreenCapturePermission()
            return
        }
        pendingScreenCaptureAfterAccessibilityPermission = true
        Toast.makeText(
            requireContext(),
            getString(R.string.screen_detection_accessibility_permission_required),
            Toast.LENGTH_SHORT
        ).show()
        accessibilityPermissionLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = requireContext().getSystemService(MediaProjectionManager::class.java)
        screenCapturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun updateScreenDetectionUi(state: ScreenDetectionState) {
        screenDetectionButton.text = getString(
            if (state.isRunning) R.string.action_stop_screen_detection
            else R.string.action_start_screen_detection
        )
    }

    private fun launchImagePicker() {
        lastSelectionUsedFilePicker = appSettingsManager.isFileSystemPickerEnabled()
        DebugLogManager.addLog("图片选择", "打开${if (lastSelectionUsedFilePicker) "文件系统" else "相册"}选择器")
        if (lastSelectionUsedFilePicker) {
            pickImagesFromFilesLauncher.launch(arrayOf("image/*"))
        } else {
            pickImagesFromGalleryLauncher.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }
    }

    private fun launchVideoPicker() {
        lastVideoSelectionUsedFilePicker = appSettingsManager.isFileSystemPickerEnabled()
        DebugLogManager.addLog("视频选择", "打开${if (lastVideoSelectionUsedFilePicker) "文件系统" else "相册"}选择器")
        if (lastVideoSelectionUsedFilePicker) {
            pickVideoFromFilesLauncher.launch(arrayOf("video/*"))
        } else {
            pickVideoFromGalleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
    }

    private fun handleVideosSelected(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(requireContext(), R.string.image_processing_no_video_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val filteredUris = if (lastVideoSelectionUsedFilePicker) {
            uris.filter { uri ->
                val type = requireContext().contentResolver.getType(uri)
                if (type != null) {
                    type.startsWith("video/")
                } else {
                    val name = mediaSelectionHelper.queryDisplayName(uri) ?: return@filter false
                    val lower = name.lowercase()
                    lower.endsWith(".mp4") ||
                        lower.endsWith(".mov") ||
                        lower.endsWith(".mkv") ||
                        lower.endsWith(".avi") ||
                        lower.endsWith(".webm")
                }
            }
        } else {
            uris
        }

        val skippedCount = uris.size - filteredUris.size
        if (skippedCount > 0) {
            DebugLogManager.addLog("视频选择", "已过滤 $skippedCount 个不支持的文件")
            Toast.makeText(
                requireContext(),
                getString(R.string.image_processing_filtered_unsupported_files, skippedCount),
                Toast.LENGTH_SHORT
            ).show()
        }

        if (filteredUris.isEmpty()) {
            Toast.makeText(requireContext(), R.string.image_processing_no_usable_video, Toast.LENGTH_SHORT).show()
            return
        }
        resolveNameConflictsBeforeProcessing(filteredUris) { resolvedUris ->
            if (resolvedUris.isEmpty()) {
                Toast.makeText(requireContext(), R.string.image_processing_duplicate_videos_skipped, Toast.LENGTH_SHORT).show()
                return@resolveNameConflictsBeforeProcessing
            }
            if (lastVideoSelectionUsedFilePicker) {
                mediaSelectionHelper.persistReadPermissions(resolvedUris)
            }
            confirmAndStartVideoProcessing(resolvedUris)
        }
    }

    private fun handleImagesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(requireContext(), R.string.image_processing_no_image_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val filteredUris = if (lastSelectionUsedFilePicker) {
            mediaSelectionHelper.filterSupportedImageUris(uris)
        } else {
            uris
        }

        val skippedCount = uris.size - filteredUris.size
        if (skippedCount > 0) {
            DebugLogManager.addLog("图片选择", "已过滤 $skippedCount 个不支持的文件")
            Toast.makeText(
                requireContext(),
                getString(R.string.image_processing_filtered_unsupported_files, skippedCount),
                Toast.LENGTH_SHORT
            ).show()
        }

        if (filteredUris.isEmpty()) {
            Toast.makeText(requireContext(), R.string.image_processing_no_usable_image, Toast.LENGTH_SHORT).show()
            return
        }
        resolveNameConflictsBeforeProcessing(filteredUris) { resolvedUris ->
            if (resolvedUris.isEmpty()) {
                Toast.makeText(requireContext(), R.string.image_processing_duplicate_images_skipped, Toast.LENGTH_SHORT).show()
                return@resolveNameConflictsBeforeProcessing
            }
            if (lastSelectionUsedFilePicker) {
                mediaSelectionHelper.persistReadPermissions(resolvedUris)
            }
            if (resolvedUris.size == 1) {
                handleSingleImageSelected(resolvedUris.first())
            } else {
                handleBatchImagesSelected(resolvedUris)
            }
        }
    }

    private fun resolveNameConflictsBeforeProcessing(
        uris: List<Uri>,
        onResolved: (List<Uri>) -> Unit
    ) {
        lifecycleScope.launch {
            val conflictResult = withContext(Dispatchers.IO) {
                detectFileNameConflicts(uris)
            }
            if (conflictResult.conflictedNames.isEmpty()) {
                onResolved(uris)
                return@launch
            }

            val previewNames = conflictResult.conflictedNames.take(5).joinToString("\n") { "• $it" }
            val remainCount = conflictResult.conflictedNames.size - 5
            val remainSuffix = if (remainCount > 0) {
                getString(R.string.image_processing_duplicate_conflict_more_suffix, remainCount)
            } else {
                ""
            }
            val message = getString(
                R.string.image_processing_duplicate_conflict_message,
                conflictResult.conflictedNames.size,
                previewNames,
                remainSuffix
            )

            DialogUtils.builder(requireContext())
                .setTitle(R.string.image_processing_duplicate_conflict_title)
                .setMessage(message)
                .setPositiveButton(R.string.image_processing_duplicate_conflict_skip) { _, _ ->
                    DebugLogManager.addLog("重名检测", "用户选择跳过 ${conflictResult.conflictUris.size} 个同名文件")
                    onResolved(uris.filterNot { it in conflictResult.conflictUris })
                }
                .setNegativeButton(R.string.image_processing_duplicate_conflict_continue) { _, _ ->
                    DebugLogManager.addLog("重名检测", "用户选择继续处理同名文件")
                    onResolved(uris)
                }
                .setCancelable(false)
                .show()
        }
    }

    private data class NameConflictResult(
        val conflictUris: Set<Uri>,
        val conflictedNames: List<String>
    )

    private fun detectFileNameConflicts(uris: List<Uri>): NameConflictResult {
        val existingNames = loadInternalManagedFileNames()
        if (existingNames.isEmpty()) {
            return NameConflictResult(emptySet(), emptyList())
        }
        val conflictUris = mutableSetOf<Uri>()
        val conflictedNames = LinkedHashSet<String>()
        uris.forEach { uri ->
            val name = mediaSelectionHelper.queryDisplayName(uri)?.trim().orEmpty()
            if (name.isNotEmpty() && existingNames.contains(name.lowercase())) {
                conflictUris.add(uri)
                conflictedNames.add(name)
            }
        }
        return NameConflictResult(conflictUris, conflictedNames.toList())
    }

    private fun loadInternalManagedFileNames(): Set<String> {
        val rootDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val managedDirs = buildList {
            add(FolderModels.SAFE_NET_DIR)
            add(FolderModels.NO_DETECTION_DIR)
            add(FolderModels.SAFE_VIDEO_DIR)
            addAll(appSettingsManager.getCustomImageFolders())
        }
            .asSequence()
            .map { File(rootDir, it) }
            .filter { it.exists() && it.isDirectory }
            .toList()
        if (managedDirs.isEmpty()) return emptySet()

        return managedDirs.asSequence()
            .flatMap { dir -> dir.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.isFile }
            .map { it.name.lowercase() }
            .toSet()
    }

    private fun handleSingleImageSelected(uri: Uri) {
        DebugLogManager.addLog("图片选择", "开始处理选择的图片: $uri")
        try {
            val bytes = mediaSelectionHelper.readBytes(uri)
            if (bytes == null || bytes.isEmpty()) {
                DebugLogManager.addLog("错误", "无法读取图片内容")
                Toast.makeText(requireContext(), R.string.image_processing_unable_read_image, Toast.LENGTH_SHORT).show()
                return
            }

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                DebugLogManager.addLog("错误", "图片格式不受支持")
                Toast.makeText(requireContext(), R.string.image_processing_unsupported_image, Toast.LENGTH_SHORT).show()
                return
            }

            selectedBitmap = bitmap
            selectedBytes = bytes
            selectedName = mediaSelectionHelper.queryDisplayName(uri)

            DebugLogManager.addLog("图片信息", "尺寸: ${bitmap.width}x${bitmap.height}, 大小: ${bytes.size} bytes")
            DebugLogManager.addLog("图片信息", "文件名: $selectedName")

            imagePreview.setImageBitmap(bitmap)
            showSinglePreview()
            progressMode = ProgressMode.IDLE
            statusText.text = getString(R.string.status_placeholder)
            progressBar.isIndeterminate = false
            progressBar.max = 100
            progressBar.progress = 0
            runButton.isEnabled = true
        } catch (e: IOException) {
            e.printStackTrace()
            DebugLogManager.addLog("错误", "加载图片失败: ${e.message}")
            Toast.makeText(
                requireContext(),
                getString(R.string.image_processing_load_failed, e.message ?: getString(R.string.common_unknown_error)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return mediaSelectionHelper.queryDisplayName(uri)
    }

    private fun processSelection() {
        val bitmap = selectedBitmap
        val bytes = selectedBytes
        if (bitmap == null || bytes == null) {
            Toast.makeText(requireContext(), R.string.image_processing_select_image_first, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            progressMode = ProgressMode.SINGLE_IMAGE
            showLoading(true)
            progressBar.max = 100
            progressBar.isIndeterminate = false
            progressBar.progress = 10
            updateSingleProgressText(10)
            DebugLogManager.addLog("图片处理", "开始处理图片: ${selectedName ?: "未知"}")
            try {
                // 检查模型是否已加载，如果没有则先加载
                val targetVariant = appSettingsManager.getDetectionModelVariant()
                if (!isModelLoaded || loadedModelVariant != targetVariant || yoloRunner == null) {
                    progressBar.progress = 20
                    updateSingleProgressText(20)
                    DebugLogManager.addLog("模型", "开始初始化 YOLO runner: ${targetVariant.runtimeLabel}")
                    val runner = withContext(Dispatchers.IO) {
                        YoloModelProvider.getRunner(requireContext(), targetVariant)
                    }
                    yoloRunner = runner
                    isModelLoaded = true
                    loadedModelVariant = targetVariant
                    progressBar.progress = 35
                    updateSingleProgressText(35)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.image_processing_model_loaded, targetVariant.displayName),
                            Toast.LENGTH_SHORT
                        ).show()
                        DebugLogManager.addLog("模型", "YOLO 模型加载成功: ${targetVariant.runtimeLabel}")
                    }
                }
                
                progressBar.progress = 55
                updateSingleProgressText(55)
                val runner = yoloRunner
                if (runner == null) {
                    DebugLogManager.addLog("图片处理", "错误: YOLO 模型未初始化")
                    throw IllegalStateException("YOLO 模型未初始化")
                }
                
                DebugLogManager.addLog("图片处理", "开始运行模型推理")
                val detections = withContext(Dispatchers.Default) {
                    runner.run(bitmap)
                }
                
                DebugLogManager.addLog("图片处理", "推理完成，检测到 ${detections.size} 个目标")
                progressBar.progress = 80
                updateSingleProgressText(80)
                
                // 记录详细的检测结果
                if (detections.isNotEmpty()) {
                    DebugLogManager.addLog("检测结果", "检测到的目标详情:")
                    detections.forEachIndexed { index, detection ->
                        val box = detection.box
                        val scoreStr = String.format("%.3f", detection.score)
                        DebugLogManager.addLog("检测结果", "  #${index + 1}: ${detection.className} (置信度: $scoreStr)")
                        DebugLogManager.addLog("检测结果", "     位置: [${box.left.toInt()}, ${box.top.toInt()}, ${box.width().toInt()}, ${box.height().toInt()}]")
                    }
                } else {
                    DebugLogManager.addLog("检测结果", "未检测到任何目标")
                }
                
                DebugLogManager.addLog("图片处理", "开始保存结果")
                val selectedDetectedFolder = appSettingsManager.getSelectedOutputFolder()
                val saveResult = withContext(Dispatchers.IO) {
                    MediaSaveHelper.saveImage(
                        context = requireContext(),
                        bytes = bytes,
                        originalName = selectedName,
                        detections = detections,
                        preferredDetectedFolder = selectedDetectedFolder
                    )
                }
                progressBar.progress = 100
                updateSingleProgressText(100)
                
                val targetDir = if (saveResult.hasDetections) selectedDetectedFolder else FolderModels.NO_DETECTION_DIR
                DebugLogManager.addLog("图片处理", "图片已保存到: $targetDir/${saveResult.imageFile.name}")
                if (saveResult.metadataFile != null) {
                    DebugLogManager.addLog("图片处理", "元数据已保存到: $targetDir/${saveResult.metadataFile.name}")
                }
                
                updateStatus(detections, saveResult)
            } catch (e: Exception) {
                e.printStackTrace()
                DebugLogManager.addLog("图片处理", "处理失败: ${e.message}")
                DebugLogManager.addLog("图片处理", "异常堆栈: ${e.stackTraceToString()}")
                statusText.text = getString(R.string.status_error, e.message ?: "unknown error")
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                Toast.makeText(
                    requireContext(),
                    e.message ?: getString(R.string.common_unknown_error),
                    Toast.LENGTH_LONG
                ).show()
                reportHandledErrorAndPrompt(
                    source = "图片处理",
                    message = e.message ?: "Unknown error",
                    throwable = e
                )
            } finally {
                showLoading(false)
            }
        }
    }

    private fun updateStatus(
        detections: List<YoloOnnxRunner.Detection>,
        saveResult: SaveResult
    ) {
        if (detections.isEmpty()) {
            val message = getString(R.string.status_no_detection, saveResult.imageFile.name)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            return
        }

        val message = getString(
            R.string.status_detection_saved,
            detections.size,
            saveResult.imageFile.name,
            saveResult.metadataFile?.name ?: "-"
        )
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun showLoading(show: Boolean) {
        runButton.isEnabled = !show && selectedBitmap != null
        selectButton.isEnabled = !show
    }
    
    private fun handleBatchImagesSelected(uris: List<Uri>) {
        DebugLogManager.addLog("批量选择", "选择了 ${uris.size} 张图片")

        selectedBitmap = null
        selectedBytes = null
        selectedName = null
        imagePreview.setImageDrawable(null)
        showBatchPreview()
        progressMode = ProgressMode.BATCH_IMAGE
        currentBatchVideoIndex = 0
        totalBatchVideos = 0
        completedBatchVideos = 0
        runButton.isEnabled = false
        statusText.text = getString(R.string.batch_progress_compact, 0, uris.size)
        progressBar.isIndeterminate = false
        progressBar.max = uris.size
        progressBar.progress = 0

        // 启动批量处理服务
        BatchProcessingService.startProcessing(
            context = requireContext(),
            uris = ArrayList(uris),
            preferredDetectedFolder = appSettingsManager.getSelectedOutputFolder()
        )
        
        Toast.makeText(
            requireContext(),
            getString(R.string.batch_processing_started, uris.size),
            Toast.LENGTH_SHORT
        ).show()
    }
    
    private fun updateBatchUI(state: BatchProcessingManager.BatchProcessingState) {
        if (progressMode != ProgressMode.BATCH_IMAGE) return
        when (state) {
            is BatchProcessingManager.BatchProcessingState.Idle -> {
                statusText.text = getString(R.string.batch_status_ready)
                progressBar.isIndeterminate = false
                progressBar.progress = 0
            }
            is BatchProcessingManager.BatchProcessingState.Loading -> {
                statusText.text = getString(R.string.batch_status_loading)
            }
            is BatchProcessingManager.BatchProcessingState.LoadingModel -> {
                statusText.text = getString(R.string.batch_status_loading_model)
            }
            is BatchProcessingManager.BatchProcessingState.Processing -> {
                statusText.text = getString(R.string.batch_status_processing)
            }
            is BatchProcessingManager.BatchProcessingState.Completed -> {
                statusText.text = getString(R.string.batch_status_completed)
                progressBar.isIndeterminate = false
                progressBar.progress = progressBar.max
                Toast.makeText(requireContext(), R.string.batch_processing_completed, Toast.LENGTH_SHORT).show()
            }
            is BatchProcessingManager.BatchProcessingState.Cancelled -> {
                statusText.text = getString(R.string.batch_status_cancelled)
                progressBar.isIndeterminate = false
                progressBar.progress = 0
            }
            is BatchProcessingManager.BatchProcessingState.Error -> {
                statusText.text = getString(R.string.batch_status_error, state.message)
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                Toast.makeText(
                    requireContext(),
                    getString(R.string.batch_processing_failed, state.message),
                    Toast.LENGTH_LONG
                ).show()
                reportHandledErrorAndPrompt(
                    source = "批量处理",
                    message = state.message
                )
            }
        }
    }
    
    private fun updateBatchProgress(progress: BatchProcessingManager.BatchProgress) {
        if (progressMode != ProgressMode.BATCH_IMAGE) return
        if (progress.totalCount > 0) {
            progressBar.isIndeterminate = false
            progressBar.max = progress.totalCount
            progressBar.progress = progress.processedCount
            statusText.text = getString(
                R.string.batch_progress_compact,
                progress.processedCount,
                progress.totalCount
            )
        }
    }
    
    private fun handleVideoSelected(uri: Uri) {
        showSinglePreview()
        progressMode = ProgressMode.SINGLE_VIDEO
        currentBatchVideoIndex = 0
        totalBatchVideos = 0
        completedBatchVideos = 0
        if (lastVideoSelectionUsedFilePicker) {
            mediaSelectionHelper.persistReadPermissions(listOf(uri))
        }
        confirmAndStartVideoProcessing(listOf(uri))
    }

    private fun confirmAndStartVideoProcessing(videoUris: List<Uri>) {
        val blockedLabels = privacySettingsManager.getBlockedLabels()
        val displayLabels = privacySettingsManager.getDisplayNames(blockedLabels)
        val defaultBlurMode = privacySettingsManager.getBlurMode()
        val defaultBlurName = privacySettingsManager.getBlurModeName(defaultBlurMode)
        val labelOverrides = privacySettingsManager.getLabelEffectOverrides()
        val overrideSummary = blockedLabels.mapNotNull { label ->
            val override = labelOverrides[label] ?: return@mapNotNull null
            "${privacySettingsManager.getLabelDisplayName(label)}(${privacySettingsManager.getBlurModeName(override)})"
        }

        showConfirmDialog(
            title = getString(R.string.video_confirm_labels_title),
            message = if (blockedLabels.isEmpty()) {
                getString(R.string.video_confirm_labels_empty)
            } else {
                getString(R.string.video_confirm_labels_message, displayLabels.joinToString(", "))
            }
        ) {
            showConfirmDialog(
                title = getString(R.string.video_confirm_effect_title),
                message = if (overrideSummary.isEmpty()) {
                    getString(R.string.video_confirm_effect_message_default, defaultBlurName)
                } else {
                    getString(
                        R.string.video_confirm_effect_message_overrides,
                        defaultBlurName,
                        overrideSummary.joinToString(", ")
                    )
                }
            ) {
                if (videoUris.size == 1) {
                    val uri = videoUris.first()
                    DebugLogManager.addLog("视频选择", "开始处理选择的视频: $uri")
                    startVideoProcessing(uri, blockedLabels, defaultBlurMode, labelOverrides)
                } else {
                    DebugLogManager.addLog("视频选择", "开始顺序处理 ${videoUris.size} 个视频")
                    startVideoQueueProcessing(videoUris, blockedLabels, defaultBlurMode, labelOverrides)
                }
            }
        }
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        onConfirm: () -> Unit
    ) {
        DialogUtils.builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.video_confirm_positive) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.video_confirm_negative, null)
            .show()
    }

    private fun startVideoProcessing(
        uri: Uri,
        blockedLabels: List<String>,
        defaultBlurMode: Int,
        labelEffectOverrides: Map<String, Int>
    ) {
        DebugLogManager.addLog("视频处理", "收到视频任务，准备开始处理")
        if (progressMode != ProgressMode.BATCH_VIDEO) {
            progressMode = ProgressMode.SINGLE_VIDEO
            statusText.text = getString(R.string.single_progress_percent, 0)
        } else {
            statusText.text = getString(
                R.string.batch_progress_compact,
                completedBatchVideos,
                totalBatchVideos
            )
        }
        progressBar.max = 100
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        runButton.isEnabled = false
        selectButton.isEnabled = false
        selectVideoButton.isEnabled = false

        val skipStride = appSettingsManager.getVideoSkipStride()
        val reverseLabels = privacySettingsManager.getReverseLabels()
        val highLoadMode = appSettingsManager.isVideoHighLoadEnabled()
        val options = VideoProcessingManager.VideoProcessingOptions(
            blockedLabels = blockedLabels,
            reverseLabels = reverseLabels,
            blurMode = defaultBlurMode,
            labelEffectOverrides = labelEffectOverrides,
            skipStride = skipStride,
            highLoadMode = highLoadMode
        )
        DebugLogManager.addLog(
            "视频处理",
            "使用跳帧设置: 每${skipStride}帧检测一次, 高负载模式=${if (highLoadMode) "开启" else "关闭"}"
        )
        VideoProcessingService.startProcessing(
            context = requireContext(),
            uris = arrayListOf(uri),
            options = options
        )
    }

    private fun startVideoQueueProcessing(
        videoUris: List<Uri>,
        blockedLabels: List<String>,
        defaultBlurMode: Int,
        labelEffectOverrides: Map<String, Int>
    ) {
        if (videoUris.isEmpty()) return
        progressMode = ProgressMode.BATCH_VIDEO
        totalBatchVideos = videoUris.size
        completedBatchVideos = 0
        showSinglePreview()
        progressBar.max = 100
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        runButton.isEnabled = false
        selectButton.isEnabled = false
        selectVideoButton.isEnabled = false
        selectFolderButton.isEnabled = false
        statusText.text = getString(
            R.string.batch_progress_compact,
            completedBatchVideos,
            totalBatchVideos
        )

        val highLoadMode = appSettingsManager.isVideoHighLoadEnabled()
        val options = VideoProcessingManager.VideoProcessingOptions(
            blockedLabels = blockedLabels,
            reverseLabels = privacySettingsManager.getReverseLabels(),
            blurMode = defaultBlurMode,
            labelEffectOverrides = labelEffectOverrides,
            skipStride = appSettingsManager.getVideoSkipStride(),
            highLoadMode = highLoadMode
        )
        DebugLogManager.addLog(
            "视频处理",
            "启动视频队列任务: ${videoUris.size} 个, 高负载模式=${if (highLoadMode) "开启" else "关闭"}"
        )
        VideoProcessingService.startProcessing(
            context = requireContext(),
            uris = ArrayList(videoUris),
            options = options
        )
    }

    private fun updateVideoState(state: VideoProcessingManager.VideoProcessingState) {
        when (state) {
            is VideoProcessingManager.VideoProcessingState.Idle -> {
                // 保持默认 UI
            }
            is VideoProcessingManager.VideoProcessingState.Initializing -> {
                if (progressMode == ProgressMode.BATCH_VIDEO) {
                    statusText.text = getString(
                        R.string.batch_progress_compact,
                        completedBatchVideos,
                        totalBatchVideos
                    )
                } else {
                    statusText.text = getString(R.string.single_progress_percent, progressBar.progress)
                }
            }
            is VideoProcessingManager.VideoProcessingState.LoadingModel -> {
                if (progressMode == ProgressMode.BATCH_VIDEO) {
                    statusText.text = getString(
                        R.string.batch_progress_compact,
                        completedBatchVideos,
                        totalBatchVideos
                    )
                } else {
                    statusText.text = getString(R.string.single_progress_percent, progressBar.progress)
                }
            }
            is VideoProcessingManager.VideoProcessingState.Processing -> {
                progressBar.isIndeterminate = false
                if (progressMode == ProgressMode.BATCH_VIDEO) {
                    statusText.text = getString(
                        R.string.batch_progress_compact,
                        completedBatchVideos,
                        totalBatchVideos
                    )
                } else {
                    statusText.text = getString(R.string.single_progress_percent, progressBar.progress)
                }
            }
            is VideoProcessingManager.VideoProcessingState.Completed -> {
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                if (progressMode == ProgressMode.BATCH_VIDEO) {
                    completedBatchVideos = (completedBatchVideos + 1).coerceAtMost(totalBatchVideos)
                    statusText.text = getString(
                        R.string.batch_progress_compact,
                        completedBatchVideos,
                        totalBatchVideos
                    )
                    if (completedBatchVideos >= totalBatchVideos && totalBatchVideos > 0) {
                        Toast.makeText(requireContext(), R.string.image_processing_video_queue_completed, Toast.LENGTH_SHORT).show()
                        resetVideoUiLocks()
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.video_toast_completed, Toast.LENGTH_SHORT).show()
                    resetVideoUiLocks()
                    statusText.text = getString(R.string.single_progress_percent, 100)
                }
            }
            is VideoProcessingManager.VideoProcessingState.Error -> {
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                resetVideoUiLocks()
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                statusText.text = getString(R.string.status_error, state.message)
                reportHandledErrorAndPrompt(
                    source = "视频处理",
                    message = state.message
                )
            }
            is VideoProcessingManager.VideoProcessingState.Cancelled -> {
                resetVideoUiLocks()
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                statusText.text = getString(R.string.video_status_cancelled)
            }
        }
    }

    private fun updateVideoProgress(progress: VideoProcessingManager.VideoProgress) {
        if (progress.totalFrames <= 0) return
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = progress.percentage.coerceIn(0, 99)
        if (progressMode == ProgressMode.BATCH_VIDEO) {
            statusText.text = getString(
                R.string.batch_progress_compact,
                completedBatchVideos,
                totalBatchVideos
            )
        } else {
            statusText.text = getString(R.string.single_progress_percent, progressBar.progress)
        }
    }

    private fun resetVideoUiLocks() {
        runButton.isEnabled = selectedBitmap != null
        selectButton.isEnabled = true
        selectVideoButton.isEnabled = true
        selectFolderButton.isEnabled = true
    }

    private fun reportHandledErrorAndPrompt(source: String, message: String, throwable: Throwable? = null) {
        val context = activity ?: return
        val reportFile = ErrorReportManager.captureHandledError(context, source, message, throwable) ?: return
        ErrorReportManager.promptShareHandledError(context, reportFile, source)
    }

    private fun launchFolderPicker() {
        DebugLogManager.addLog("文件夹选择", "打开文件夹选择器")
        pickFolderLauncher.launch(null)
    }

    private fun handleFolderSelected(folderUri: Uri) {
        lifecycleScope.launch {
            try {
                statusText.text = getString(R.string.image_processing_scanning_folder)
                progressBar.isIndeterminate = true
                selectButton.isEnabled = false
                selectVideoButton.isEnabled = false
                selectFolderButton.isEnabled = false
                
                val scanResult = withContext(Dispatchers.IO) {
                    folderMediaScanner.scan(folderUri) { log ->
                        DebugLogManager.addLog("文件夹扫描", log)
                    }
                }
                val imageUris = scanResult.imageUris
                val videoUris = scanResult.videoUris
                
                progressBar.isIndeterminate = false
                progressBar.max = 100
                progressBar.progress = 0
                selectButton.isEnabled = true
                selectVideoButton.isEnabled = true
                selectFolderButton.isEnabled = true
                
                if (imageUris.isEmpty() && videoUris.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.image_processing_folder_no_media, Toast.LENGTH_SHORT).show()
                    progressMode = ProgressMode.IDLE
                    statusText.text = getString(R.string.image_processing_folder_no_media_status)
                    return@launch
                }
                
                val message = getString(
                    R.string.image_processing_folder_confirm_message,
                    imageUris.size,
                    videoUris.size
                )
                
                DebugLogManager.addLog("文件夹选择", "扫描到 ${imageUris.size} 张图片, ${videoUris.size} 个视频")
                
                DialogUtils.builder(requireContext())
                    .setTitle(R.string.image_processing_folder_confirm_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.image_processing_folder_confirm_start) { _, _ ->
                        processFolderMedia(imageUris, videoUris)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    
            } catch (e: Exception) {
                progressBar.isIndeterminate = false
                progressBar.max = 100
                progressBar.progress = 0
                selectButton.isEnabled = true
                selectVideoButton.isEnabled = true
                selectFolderButton.isEnabled = true
                progressMode = ProgressMode.IDLE
                DebugLogManager.addLog("文件夹选择", "扫描失败: ${e.message}")
                Toast.makeText(
                    requireContext(),
                    getString(R.string.image_processing_folder_scan_failed, e.message ?: getString(R.string.common_unknown_error)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun processFolderMedia(imageUris: List<Uri>, videoUris: List<Uri>) {
        lifecycleScope.launch {
            val resolvedImages = resolveNameConflictsBeforeProcessingSuspend(imageUris)
            val resolvedVideos = resolveNameConflictsBeforeProcessingSuspend(videoUris)

            if (imageUris.isNotEmpty() && resolvedImages.isEmpty()) {
                Toast.makeText(requireContext(), R.string.image_processing_duplicate_images_skipped, Toast.LENGTH_SHORT).show()
            }
            if (videoUris.isNotEmpty() && resolvedVideos.isEmpty()) {
                Toast.makeText(requireContext(), R.string.image_processing_duplicate_videos_skipped, Toast.LENGTH_SHORT).show()
            }

            if (resolvedImages.isNotEmpty()) {
                DebugLogManager.addLog("文件夹处理", "开始处理 ${resolvedImages.size} 张图片")
                handleBatchImagesSelected(resolvedImages)

                if (resolvedVideos.isNotEmpty()) {
                    batchManager.processingState.first { state ->
                        state is BatchProcessingManager.BatchProcessingState.Completed ||
                            state is BatchProcessingManager.BatchProcessingState.Error ||
                            state is BatchProcessingManager.BatchProcessingState.Cancelled
                    }
                    startVideoQueueProcessing(
                        videoUris = resolvedVideos,
                        blockedLabels = privacySettingsManager.getBlockedLabels(),
                        defaultBlurMode = privacySettingsManager.getBlurMode(),
                        labelEffectOverrides = privacySettingsManager.getLabelEffectOverrides()
                    )
                }
            } else if (resolvedVideos.isNotEmpty()) {
                startVideoQueueProcessing(
                    videoUris = resolvedVideos,
                    blockedLabels = privacySettingsManager.getBlockedLabels(),
                    defaultBlurMode = privacySettingsManager.getBlurMode(),
                    labelEffectOverrides = privacySettingsManager.getLabelEffectOverrides()
                )
            }
        }
    }

    private suspend fun resolveNameConflictsBeforeProcessingSuspend(uris: List<Uri>): List<Uri> {
        if (uris.isEmpty()) return emptyList()
        return suspendCancellableCoroutine { cont ->
            resolveNameConflictsBeforeProcessing(uris) { resolved ->
                if (cont.isActive) cont.resume(resolved)
            }
        }
    }

    private fun showSinglePreview() {
        imagePreview.visibility = View.VISIBLE
        batchResultsRecyclerView.visibility = View.GONE
    }

    private fun showBatchPreview() {
        imagePreview.visibility = View.GONE
        batchResultsRecyclerView.visibility = View.VISIBLE
    }

    private fun updateSingleProgressText(percent: Int) {
        statusText.text = getString(R.string.single_progress_percent, percent.coerceIn(0, 100))
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理批量处理管理器
        batchManager.clearResults()
    }
}
