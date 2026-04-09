package com.safe.vision

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryFolderFullscreenFragment : Fragment() {

    companion object {
        private const val ARG_FOLDER_TYPE = "folder_type"
        private const val ARG_CUSTOM_FOLDER_NAME = "custom_folder_name"
        private const val ARG_CUSTOM_FOLDER_TITLE = "custom_folder_title"
        private const val RESULT_KEY = "folder_fullscreen_result"
        private const val THUMB_SIZE = 320

        fun newInstance(folderType: FolderType): GalleryFolderFullscreenFragment {
            return GalleryFolderFullscreenFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FOLDER_TYPE, folderType.name)
                }
            }
        }

        fun newInstanceCustom(folderName: String, folderTitle: String): GalleryFolderFullscreenFragment {
            return GalleryFolderFullscreenFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FOLDER_TYPE, FolderType.SAFE_NET.name)
                    putString(ARG_CUSTOM_FOLDER_NAME, folderName)
                    putString(ARG_CUSTOM_FOLDER_TITLE, folderTitle)
                }
            }
        }
    }

    private lateinit var folderType: FolderType
    private var customFolderName: String? = null
    private var customFolderTitle: String? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var folderTitle: TextView
    private lateinit var folderCount: TextView
    private lateinit var emptyText: TextView
    private lateinit var selectionActionBar: View
    private lateinit var selectionCountText: TextView
    private lateinit var moveSelectedButton: View
    private lateinit var selectAllButton: View
    private lateinit var saveSelectedButton: View
    private lateinit var deleteSelectedButton: View
    private lateinit var cancelSelectionButton: View
    private lateinit var importNoDetectionButton: View
    private lateinit var thumbnailCacheManager: ThumbnailCacheManager
    private lateinit var privacyProcessor: ImagePrivacyProcessor
    private lateinit var appSettingsManager: AppSettingsManager
    private lateinit var mediaSelectionHelper: MediaSelectionHelper
    private val saveDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var mediaFiles: List<File> = emptyList()
    private val selectedPaths = mutableSetOf<String>()
    private var selectionMode = false
    private var changed = false
    private var mediaAdapter: FolderMediaAdapter? = null
    private var lastImportUsedFilePicker = false

    private val pickImagesFromGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        handleImportImagesSelected(uris)
    }

    private val pickImagesFromFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        handleImportImagesSelected(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderType = FolderType.valueOf(
            requireArguments().getString(ARG_FOLDER_TYPE) ?: FolderType.SAFE_NET.name
        )
        customFolderName = requireArguments().getString(ARG_CUSTOM_FOLDER_NAME)
        customFolderTitle = requireArguments().getString(ARG_CUSTOM_FOLDER_TITLE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_gallery_folder_fullscreen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.folderRecyclerView)
        folderTitle = view.findViewById(R.id.folderTitle)
        folderCount = view.findViewById(R.id.folderCount)
        emptyText = view.findViewById(R.id.fullscreenEmptyText)
        selectionActionBar = view.findViewById(R.id.selectionActionBar)
        selectionCountText = view.findViewById(R.id.selectionCountText)
        moveSelectedButton = view.findViewById(R.id.btnMoveSelected)
        selectAllButton = view.findViewById(R.id.btnSelectAllCurrentPage)
        saveSelectedButton = view.findViewById(R.id.btnSaveSelected)
        deleteSelectedButton = view.findViewById(R.id.btnDeleteSelected)
        cancelSelectionButton = view.findViewById(R.id.btnCancelSelection)
        importNoDetectionButton = view.findViewById(R.id.btnImportNoDetectionImages)
        thumbnailCacheManager = ThumbnailCacheManager.getInstance(requireContext())
        privacyProcessor = ImagePrivacyProcessor(requireContext())
        appSettingsManager = AppSettingsManager.getInstance(requireContext())
        mediaSelectionHelper = MediaSelectionHelper(requireContext().contentResolver)

        view.findViewById<View>(R.id.btnCloseFullscreen).setOnClickListener { closeSelf() }
        moveSelectedButton.setOnClickListener { showMoveDialog() }
        selectAllButton.setOnClickListener { selectAllVisible() }
        saveSelectedButton.setOnClickListener { saveSelected() }
        deleteSelectedButton.setOnClickListener { confirmDeleteSelected() }
        cancelSelectionButton.setOnClickListener { exitSelectionMode() }
        importNoDetectionButton.setOnClickListener { launchNoDetectionImagePicker() }
        importNoDetectionButton.visibility =
            if (folderType == FolderType.NO_DETECTION && customFolderName.isNullOrBlank()) View.VISIBLE else View.GONE

        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        mediaAdapter = FolderMediaAdapter(
            thumbnailCacheManager = thumbnailCacheManager,
            onClick = { file ->
                if (selectionMode) {
                    toggleSelection(file)
                } else {
                    (requireActivity() as? MainActivity)?.openImageViewer(file)
                }
            },
            onLongPress = { file ->
                if (!selectionMode) {
                    enterSelectionMode(file)
                } else {
                    toggleSelection(file)
                }
            }
        )
        recyclerView.adapter = mediaAdapter

        updateTitle()
        loadMedia()
    }

    override fun onResume() {
        super.onResume()
        loadMedia()
    }

    private fun updateTitle() {
        folderTitle.text = customFolderTitle ?: when (folderType) {
            FolderType.SAFE_NET -> getString(R.string.gallery_fullscreen_title_safenet)
            FolderType.NO_DETECTION -> getString(R.string.gallery_fullscreen_title_no_detection)
            FolderType.VIDEO -> getString(R.string.gallery_fullscreen_title_video)
        }
    }

    private fun loadMedia() {
        mediaFiles = resolveFolderFiles(folderType)
        mediaAdapter?.updateData(
            files = mediaFiles,
            mediaKind = if (isVideoFolder()) ThumbnailCacheManager.MediaKind.VIDEO else ThumbnailCacheManager.MediaKind.IMAGE,
            highlightMetadataInNoDetection = folderType == FolderType.NO_DETECTION && !isVideoFolder()
        )
        mediaAdapter?.updateSelection(selectionMode, selectedPaths)
        folderCount.text = when {
            isVideoFolder() ->
                getString(R.string.gallery_video_count, mediaFiles.size)
            else ->
                getString(R.string.gallery_image_count, mediaFiles.size)
        }
        emptyText.visibility = if (mediaFiles.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (mediaFiles.isEmpty()) View.GONE else View.VISIBLE
        cleanupSelection()
    }

    private fun isVideoFolder(): Boolean {
        if (!customFolderName.isNullOrBlank()) return false
        return folderType == FolderType.VIDEO
    }

    private fun currentFolderName(): String {
        return customFolderName ?: when (folderType) {
            FolderType.SAFE_NET -> FolderModels.SAFE_NET_DIR
            FolderType.NO_DETECTION -> FolderModels.NO_DETECTION_DIR
            FolderType.VIDEO -> FolderModels.SAFE_VIDEO_DIR
        }
    }

    private fun resolveFolderFiles(type: FolderType): List<File> {
        val rootDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val dir = File(rootDir, currentFolderName())
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file ->
            file.isFile && when {
                isVideoFolder() -> file.extension.lowercase() in listOf("mp4", "mov", "mkv")
                else -> file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")
            }
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun enterSelectionMode(initial: File) {
        selectionMode = true
        selectedPaths.add(initial.absolutePath)
        selectionActionBar.visibility = View.VISIBLE
        refreshSelectionBar()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedPaths.clear()
        selectionActionBar.visibility = View.GONE
        mediaAdapter?.updateSelection(false, emptySet())
    }

    private fun toggleSelection(file: File) {
        val path = file.absolutePath
        if (selectedPaths.contains(path)) selectedPaths.remove(path) else selectedPaths.add(path)
        if (selectedPaths.isEmpty()) {
            exitSelectionMode()
        } else {
            refreshSelectionBar()
        }
    }

    private fun refreshSelectionBar() {
        selectionCountText.text = getString(R.string.gallery_selection_count, selectedPaths.size)
        selectAllButton.isEnabled = mediaFiles.isNotEmpty()
        deleteSelectedButton.isEnabled = selectedPaths.isNotEmpty()
        saveSelectedButton.isEnabled = selectedPaths.isNotEmpty() && !isVideoFolder()
        moveSelectedButton.isEnabled = selectedPaths.isNotEmpty() && !isVideoFolder()
        moveSelectedButton.alpha = if (moveSelectedButton.isEnabled) 1f else 0.45f
        mediaAdapter?.updateSelection(selectionMode, selectedPaths)
    }

    private fun selectAllVisible() {
        if (!selectionMode) return
        selectedPaths.addAll(mediaFiles.map { it.absolutePath })
        refreshSelectionBar()
    }

    private fun cleanupSelection() {
        if (!selectionMode) return
        val current = mediaFiles.map { it.absolutePath }.toSet()
        selectedPaths.retainAll(current)
        if (selectedPaths.isEmpty()) {
            exitSelectionMode()
        } else {
            refreshSelectionBar()
        }
    }

    private fun launchNoDetectionImagePicker() {
        if (!(folderType == FolderType.NO_DETECTION && customFolderName.isNullOrBlank())) return
        lastImportUsedFilePicker = appSettingsManager.isFileSystemPickerEnabled()
        if (lastImportUsedFilePicker) {
            pickImagesFromFilesLauncher.launch(arrayOf("image/*"))
        } else {
            pickImagesFromGalleryLauncher.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }
    }

    private fun handleImportImagesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(requireContext(), R.string.gallery_import_images_none, Toast.LENGTH_SHORT).show()
            return
        }
        val filteredUris = if (lastImportUsedFilePicker) {
            mediaSelectionHelper.filterSupportedImageUris(uris)
        } else {
            uris
        }
        if (filteredUris.isEmpty()) {
            Toast.makeText(requireContext(), R.string.gallery_import_images_all_skipped, Toast.LENGTH_SHORT).show()
            return
        }
        if (lastImportUsedFilePicker) {
            mediaSelectionHelper.persistReadPermissions(filteredUris)
        }
        importNoDetectionButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val success = importImagesToNoDetection(filteredUris)
            withContext(Dispatchers.Main) {
                importNoDetectionButton.isEnabled = true
                when {
                    success == filteredUris.size -> Toast.makeText(
                        requireContext(),
                        getString(R.string.gallery_import_images_success, success),
                        Toast.LENGTH_SHORT
                    ).show()
                    success > 0 -> Toast.makeText(
                        requireContext(),
                        getString(R.string.gallery_import_images_partial, success, filteredUris.size),
                        Toast.LENGTH_SHORT
                    ).show()
                    else -> Toast.makeText(requireContext(), R.string.gallery_import_images_failed, Toast.LENGTH_SHORT).show()
                }
                if (success > 0) {
                    changed = true
                    loadMedia()
                }
            }
        }
    }

    private fun importImagesToNoDetection(uris: List<Uri>): Int {
        val rootDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val targetDir = File(rootDir, FolderModels.NO_DETECTION_DIR).apply { if (!exists()) mkdirs() }
        var success = 0
        uris.forEach { uri ->
            val displayName = mediaSelectionHelper.queryDisplayName(uri).orEmpty().trim()
            val sourceBase = displayName.substringBeforeLast('.', "").ifBlank {
                "import_${System.currentTimeMillis()}"
            }
            val extension = resolveImageExtension(uri, displayName)
            val resolvedBaseName = resolveAvailableBaseName(
                targetDir = targetDir,
                sourceBaseName = sourceBase,
                extension = extension
            )
            val target = File(targetDir, "$resolvedBaseName.$extension")
            val copied = try {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } != null
            } catch (_: Exception) {
                false
            }
            if (copied) {
                success++
                thumbnailCacheManager.invalidate(target)
            } else {
                target.delete()
            }
        }
        return success
    }

    private fun resolveImageExtension(uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        if (fromName in setOf("jpg", "jpeg", "png", "webp")) return fromName
        val mimeType = requireContext().contentResolver.getType(uri)?.lowercase(Locale.getDefault())
        return when {
            mimeType == "image/png" -> "png"
            mimeType == "image/webp" -> "webp"
            mimeType?.startsWith("image/") == true -> "jpg"
            else -> "jpg"
        }
    }

    private fun getSelectedFiles(): List<File> {
        if (selectedPaths.isEmpty()) return emptyList()
        return mediaFiles.filter { selectedPaths.contains(it.absolutePath) }
    }

    private fun confirmDeleteSelected() {
        val targets = getSelectedFiles()
        if (targets.isEmpty()) return
        val message = if (targets.size == 1) {
            getString(R.string.gallery_delete_message, targets.first().name)
        } else {
            getString(R.string.gallery_delete_message_batch, targets.size)
        }
        DialogUtils.builder(requireContext())
            .setTitle(getString(R.string.gallery_delete_title))
            .setMessage(message)
            .setPositiveButton(R.string.gallery_delete_action) { _, _ -> deleteSelected(targets) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSelected(targets: List<File>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var success = 0
            targets.forEach { file ->
                if (file.delete()) {
                    success++
                    changed = true
                    if (!isVideoFolder()) {
                        deleteMetadataFile(file)
                    }
                    thumbnailCacheManager.invalidate(file)
                }
            }
            withContext(Dispatchers.Main) {
                if (success > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.gallery_delete_success_batch, success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                loadMedia()
                if (selectionMode) exitSelectionMode()
            }
        }
    }

    private fun saveSelected() {
        if (isVideoFolder()) return
        val targets = getSelectedFiles()
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.gallery_save_empty), Toast.LENGTH_SHORT).show()
            return
        }
        saveSelectedButton.isEnabled = false
        deleteSelectedButton.isEnabled = false
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                targets.map { file -> saveImageToGallery(appContext, file) }
            }
            val successCount = results.count { it }
            val total = results.size
            changed = changed || successCount > 0
            when {
                successCount == total -> Toast.makeText(
                    requireContext(),
                    getString(R.string.gallery_save_success, successCount),
                    Toast.LENGTH_SHORT
                ).show()
                successCount > 0 -> Toast.makeText(
                    requireContext(),
                    getString(R.string.gallery_save_partial, successCount, total),
                    Toast.LENGTH_SHORT
                ).show()
                else -> Toast.makeText(
                    requireContext(),
                    getString(R.string.gallery_save_failed, "未能写入图片"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            saveSelectedButton.isEnabled = true
            deleteSelectedButton.isEnabled = true
            exitSelectionMode()
        }
    }

    private fun showMoveDialog() {
        if (isVideoFolder()) return
        val targets = getSelectedFiles()
        if (targets.isEmpty()) return

        val targetFolders = buildMoveTargetFolders()
        if (targetFolders.isEmpty()) {
            Toast.makeText(requireContext(), R.string.gallery_move_no_target_folder, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = targetFolders.toTypedArray()
        DialogUtils.builder(requireContext())
            .setTitle(R.string.gallery_move_dialog_title)
            .setItems(labels) { _, which ->
                moveSelectedToFolder(targets, targetFolders[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildMoveTargetFolders(): List<String> {
        val appSettingsManager = AppSettingsManager.getInstance(requireContext())
        val currentFolderName = currentFolderName()
        val outputCandidates = buildList {
            add(FolderModels.SAFE_NET_DIR)
            addAll(appSettingsManager.getCustomImageFolders())
        }
        return outputCandidates
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .filterNot { it.equals(currentFolderName, ignoreCase = true) }
    }

    private fun moveSelectedToFolder(targets: List<File>, targetFolder: String) {
        moveSelectedButton.isEnabled = false
        saveSelectedButton.isEnabled = false
        deleteSelectedButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val rootDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
            val targetDir = File(rootDir, targetFolder).apply { if (!exists()) mkdirs() }
            var success = 0
            targets.forEach { sourceFile ->
                val moved = moveSingleImageWithMetadata(sourceFile, targetDir)
                if (moved) {
                    success++
                    changed = true
                    thumbnailCacheManager.invalidate(sourceFile)
                }
            }
            withContext(Dispatchers.Main) {
                if (success > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.gallery_move_success, success, targetFolder),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(requireContext(), R.string.gallery_move_failed, Toast.LENGTH_SHORT).show()
                }
                moveSelectedButton.isEnabled = true
                saveSelectedButton.isEnabled = true
                deleteSelectedButton.isEnabled = true
                loadMedia()
                if (selectionMode) exitSelectionMode()
            }
        }
    }

    private fun moveSingleImageWithMetadata(sourceFile: File, targetDir: File): Boolean {
        if (!sourceFile.exists()) return false
        val extension = sourceFile.extension.lowercase(Locale.getDefault())
        val resolvedBaseName = resolveAvailableBaseName(
            targetDir = targetDir,
            sourceBaseName = sourceFile.nameWithoutExtension,
            extension = extension
        )
        val targetFile = File(targetDir, "$resolvedBaseName.$extension")
        val movedImage = moveFile(sourceFile, targetFile)
        if (!movedImage) return false

        val sourceMetadata = buildMetadataFile(sourceFile)
        if (sourceMetadata != null && sourceMetadata.exists()) {
            val targetMetadata = File(targetDir, "$resolvedBaseName.json")
            val movedMeta = moveFile(sourceMetadata, targetMetadata)
            if (!movedMeta) {
                targetFile.delete()
                return false
            }
        }
        return true
    }

    private fun resolveAvailableBaseName(
        targetDir: File,
        sourceBaseName: String,
        extension: String
    ): String {
        var candidate = sourceBaseName
        var index = 1
        while (true) {
            val imageExists = File(targetDir, "$candidate.$extension").exists()
            val jsonExists = File(targetDir, "$candidate.json").exists()
            if (!imageExists && !jsonExists) return candidate
            candidate = "${sourceBaseName}_$index"
            index++
        }
    }

    private fun moveFile(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        return try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            source.delete()
        } catch (_: Exception) {
            target.delete()
            false
        }
    }

    private fun saveImageToGallery(appContext: Context, imageFile: File): Boolean {
        var insertedUri: android.net.Uri? = null
        return try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
            val metadataFile = buildMetadataFile(imageFile)
            val processed = privacyProcessor.applyPrivacyBlur(bitmap, metadataFile)
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
            val displayName = "${imageFile.nameWithoutExtension}_masked_${saveDateFormat.format(Date())}.$suffix"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SafeVision")
                } else {
                    val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SafeVision").apply {
                        if (!exists()) mkdirs()
                    }
                    put(MediaStore.MediaColumns.DATA, File(targetDir, displayName).absolutePath)
                }
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            insertedUri = uri
            resolver.openOutputStream(uri)?.use { output ->
                val format = when (mimeType) {
                    "image/png" -> android.graphics.Bitmap.CompressFormat.PNG
                    "image/webp" -> android.graphics.Bitmap.CompressFormat.WEBP
                    else -> android.graphics.Bitmap.CompressFormat.JPEG
                }
                if (!processed.compress(format, 95, output)) {
                    throw IllegalStateException("压缩失败")
                }
            } ?: throw IllegalStateException("无法写入输出流")
            true
        } catch (e: Exception) {
            insertedUri?.let { appContext.contentResolver.delete(it, null, null) }
            false
        }
    }

    private fun buildMetadataFile(imageFile: File): File? {
        val parentDir = imageFile.parentFile ?: return null
        return parentDir.listFiles()?.firstOrNull { file ->
            file.isFile &&
                file.nameWithoutExtension.equals(imageFile.nameWithoutExtension, ignoreCase = true) &&
                file.extension.equals("json", ignoreCase = true)
        }
    }

    private fun deleteMetadataFile(imageFile: File) {
        val metadataFile = buildMetadataFile(imageFile) ?: return
        metadataFile.delete()
    }

    private fun closeSelf() {
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            Bundle().apply { putBoolean("changed", changed) }
        )
    }
}

private class FolderMediaAdapter(
    private val thumbnailCacheManager: ThumbnailCacheManager,
    private val onClick: (File) -> Unit,
    private val onLongPress: (File) -> Unit
) : RecyclerView.Adapter<FolderMediaAdapter.FolderMediaViewHolder>() {
    private var files: List<File> = emptyList()
    private var selectionMode = false
    private var selectedPaths: Set<String> = emptySet()
    private var mediaKind = ThumbnailCacheManager.MediaKind.IMAGE
    private var highlightMetadataInNoDetection = false
    private var metadataImagePaths: Set<String> = emptySet()

    fun updateData(
        files: List<File>,
        mediaKind: ThumbnailCacheManager.MediaKind,
        highlightMetadataInNoDetection: Boolean
    ) {
        this.files = files
        this.mediaKind = mediaKind
        this.highlightMetadataInNoDetection = highlightMetadataInNoDetection && mediaKind == ThumbnailCacheManager.MediaKind.IMAGE
        metadataImagePaths = if (this.highlightMetadataInNoDetection) {
            buildMetadataImagePathSet(files)
        } else {
            emptySet()
        }
        notifyDataSetChanged()
    }

    fun updateSelection(selectionMode: Boolean, selectedPaths: Set<String>) {
        this.selectionMode = selectionMode
        this.selectedPaths = selectedPaths
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderMediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_media_grid, parent, false)
        return FolderMediaViewHolder(view, thumbnailCacheManager)
    }

    override fun onBindViewHolder(holder: FolderMediaViewHolder, position: Int) {
        val file = files[position]
        holder.bind(
            file = file,
            mediaKind = mediaKind,
            selectionMode = selectionMode,
            selected = selectedPaths.contains(file.absolutePath),
            highlightMetadata = highlightMetadataInNoDetection && metadataImagePaths.contains(file.absolutePath)
        )
        holder.itemView.setOnClickListener { onClick(file) }
        holder.itemView.setOnLongClickListener {
            onLongPress(file)
            true
        }
    }

    override fun getItemCount(): Int = files.size

    override fun onViewRecycled(holder: FolderMediaViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    class FolderMediaViewHolder(
        itemView: View,
        private val thumbnailCacheManager: ThumbnailCacheManager
    ) : RecyclerView.ViewHolder(itemView) {
        private val cardView = itemView as MaterialCardView
        private val imageThumbnail = itemView.findViewById<ImageView>(R.id.imageThumbnail)
        private val mediaBadge = itemView.findViewById<TextView>(R.id.mediaBadge)
        private val selectionCheck = itemView.findViewById<CheckBox>(R.id.selectionCheck)
        private val selectionOverlay = itemView.findViewById<View>(R.id.selectionOverlay)
        private var bindToken = 0L
        private var bindPath: String? = null

        fun bind(
            file: File,
            mediaKind: ThumbnailCacheManager.MediaKind,
            selectionMode: Boolean,
            selected: Boolean,
            highlightMetadata: Boolean
        ) {
            bindToken++
            val token = bindToken
            bindPath = file.absolutePath
            val context = itemView.context
            imageThumbnail.setImageResource(
                if (mediaKind == ThumbnailCacheManager.MediaKind.VIDEO) android.R.drawable.ic_media_play
                else android.R.drawable.ic_menu_gallery
            )
            thumbnailCacheManager.load(file, mediaKind, 320) { bitmap ->
                if (bindToken != token || bindPath != file.absolutePath) return@load
                if (bitmap != null) {
                    imageThumbnail.setImageBitmap(bitmap)
                }
            }

            if (mediaKind == ThumbnailCacheManager.MediaKind.VIDEO) {
                mediaBadge.visibility = View.VISIBLE
                mediaBadge.text = readDuration(file)
            } else {
                mediaBadge.visibility = View.GONE
            }
            selectionCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
            selectionOverlay.visibility = if (selectionMode && selected) View.VISIBLE else View.GONE
            selectionCheck.isChecked = selected
            val defaultStrokeColor = MaterialColors.getColor(cardView, R.attr.svColorBorder)
            val highlightStrokeColor = MaterialColors.getColor(cardView, R.attr.svColorAccent)
            val density = context.resources.displayMetrics.density
            cardView.strokeColor = if (highlightMetadata) highlightStrokeColor else defaultStrokeColor
            cardView.strokeWidth = if (highlightMetadata) (2f * density).toInt() else (1f * density).toInt()
        }

        fun unbind() {
            bindPath = null
            bindToken++
        }

        private fun readDuration(videoFile: File): String {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                if (durationMs == null) {
                    "--:--"
                } else {
                    val seconds = durationMs / 1000
                    val minutes = seconds / 60
                    val remain = seconds % 60
                    String.format(Locale.getDefault(), "%d:%02d", minutes, remain)
                }
            } catch (_: Exception) {
                "--:--"
            }
        }
    }

    private fun buildMetadataImagePathSet(files: List<File>): Set<String> {
        if (files.isEmpty()) return emptySet()
        return files.groupBy { it.parentFile?.absolutePath.orEmpty() }
            .flatMap { (_, groupFiles) ->
                val parent = groupFiles.firstOrNull()?.parentFile ?: return@flatMap emptyList()
                val metadataNames = parent.listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                    ?.map { it.nameWithoutExtension.lowercase(Locale.ROOT) }
                    ?.toSet()
                    ?: emptySet()
                groupFiles
                    .filter { metadataNames.contains(it.nameWithoutExtension.lowercase(Locale.ROOT)) }
                    .map { it.absolutePath }
            }
            .toSet()
    }
}
