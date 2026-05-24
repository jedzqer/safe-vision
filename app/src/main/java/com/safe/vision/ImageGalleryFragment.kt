package com.safe.vision

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class ImageGalleryFragment : Fragment() {
    companion object {
        private const val FULLSCREEN_TAG = "gallery_folder_fullscreen"
        private const val RESULT_KEY = "folder_fullscreen_result"
        private const val PREVIEW_SIZE = 320
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv")
    }

    private data class GalleryFolderCard(
        val folderName: String,
        val title: String,
        val isSystem: Boolean,
        val isVideo: Boolean,
        val selectable: Boolean,
        val selected: Boolean,
        val count: Int,
        val previewFiles: List<File>
    )

    private lateinit var appSettingsManager: AppSettingsManager
    private lateinit var folderCardRecyclerView: RecyclerView
    private lateinit var addOutputFolderFab: View
    private lateinit var emptyText: TextView
    private lateinit var folderSummaryContainer: View
    private lateinit var folderFullscreenContainer: View
    private lateinit var thumbnailCacheManager: ThumbnailCacheManager

    private var cards: List<GalleryFolderCard> = emptyList()
    private lateinit var cardAdapter: FolderCardAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_image_gallery, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appSettingsManager = AppSettingsManager.getInstance(requireContext())
        folderCardRecyclerView = view.findViewById(R.id.folderCardRecyclerView)
        addOutputFolderFab = view.findViewById(R.id.addOutputFolderFab)
        emptyText = view.findViewById(R.id.emptyText)
        folderSummaryContainer = view.findViewById(R.id.folderSummaryContainer)
        folderFullscreenContainer = view.findViewById(R.id.folderFullscreenContainer)
        thumbnailCacheManager = ThumbnailCacheManager.getInstance(requireContext())

        cardAdapter = FolderCardAdapter(
            thumbnailCacheManager = thumbnailCacheManager,
            onOpen = { card -> openFolder(card) },
            onSelect = { card -> selectOutputFolder(card) },
            onLongPress = { card -> showCustomFolderActions(card) }
        )
        folderCardRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        folderCardRecyclerView.adapter = cardAdapter

        addOutputFolderFab.setOnClickListener { showCreateFolderDialog() }

        childFragmentManager.setFragmentResultListener(RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            val changed = bundle.getBoolean("changed", false)
            childFragmentManager.findFragmentByTag(FULLSCREEN_TAG)?.let { fragment ->
                childFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
            }
            folderFullscreenContainer.visibility = View.GONE
            addOutputFolderFab.visibility = View.VISIBLE
            if (changed) loadFolderCards()
        }

        loadFolderCards()
    }

    override fun onResume() {
        super.onResume()
        addOutputFolderFab.visibility =
            if (folderFullscreenContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (folderFullscreenContainer.visibility != View.VISIBLE) {
            loadFolderCards()
        }
    }

    private fun loadFolderCards() {
        val rootDir = getRootDir()
        val customFolders = appSettingsManager.getCustomImageFolders()
        val selectableFolders = listOf(FolderModels.SAFE_NET_DIR) + customFolders

        var selected = appSettingsManager.getSelectedOutputFolder()
        val selectedValid = selectableFolders.any { it.equals(selected, ignoreCase = true) } &&
            File(rootDir, selected).exists()
        if (!selectedValid) {
            selected = FolderModels.SAFE_NET_DIR
            appSettingsManager.setSelectedOutputFolder(selected)
        }

        val built = mutableListOf<GalleryFolderCard>()
        built.add(buildCard(FolderModels.SAFE_NET_DIR, getString(R.string.gallery_safenet), true, false, true, selected))
        customFolders.forEach { name ->
            built.add(buildCard(name, name, false, false, true, selected))
        }
        built.add(buildCard(FolderModels.NO_DETECTION_DIR, getString(R.string.gallery_no_detection), true, false, false, selected))
        built.add(buildCard(FolderModels.SAFE_VIDEO_DIR, getString(R.string.gallery_video_title), true, true, false, selected))

        cards = built
        cardAdapter.submitList(built)

        val allEmpty = built.all { it.count == 0 }
        emptyText.visibility = if (allEmpty) View.VISIBLE else View.GONE
        folderSummaryContainer.visibility = if (allEmpty) View.GONE else View.VISIBLE
    }

    private fun buildCard(
        folderName: String,
        title: String,
        isSystem: Boolean,
        isVideo: Boolean,
        selectable: Boolean,
        selectedFolder: String
    ): GalleryFolderCard {
        val files = resolveFolderFiles(folderName, isVideo)
        return GalleryFolderCard(
            folderName = folderName,
            title = title,
            isSystem = isSystem,
            isVideo = isVideo,
            selectable = selectable,
            selected = selectable && folderName.equals(selectedFolder, ignoreCase = true),
            count = files.size,
            previewFiles = files.take(2)
        )
    }

    private fun openFolder(card: GalleryFolderCard) {
        if (card.count <= 0) return
        if (childFragmentManager.findFragmentByTag(FULLSCREEN_TAG) != null) return
        folderFullscreenContainer.visibility = View.VISIBLE
        addOutputFolderFab.visibility = View.GONE
        val fragment = when {
            card.folderName.equals(FolderModels.SAFE_NET_DIR, ignoreCase = true) -> {
                GalleryFolderFullscreenFragment.newInstance(FolderType.SAFE_NET)
            }
            card.folderName.equals(FolderModels.NO_DETECTION_DIR, ignoreCase = true) -> {
                GalleryFolderFullscreenFragment.newInstance(FolderType.NO_DETECTION)
            }
            card.folderName.equals(FolderModels.SAFE_VIDEO_DIR, ignoreCase = true) -> {
                GalleryFolderFullscreenFragment.newInstance(FolderType.VIDEO)
            }
            else -> GalleryFolderFullscreenFragment.newInstanceCustom(card.folderName, card.title)
        }

        childFragmentManager.beginTransaction()
            .add(R.id.folderFullscreenContainer, fragment, FULLSCREEN_TAG)
            .commitAllowingStateLoss()
    }

    private fun selectOutputFolder(card: GalleryFolderCard) {
        if (!card.selectable) return
        appSettingsManager.setSelectedOutputFolder(card.folderName)
        loadFolderCards()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.gallery_create_folder_hint)
        DialogUtils.styleEditText(input)
        DialogUtils.ensureDialogLayoutParams(input)
        DialogUtils.builder(requireContext())
            .setTitle(R.string.gallery_create_folder)
            .setView(input)
            .setPositiveButton(R.string.gallery_create_folder_confirm) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                createCustomFolder(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createCustomFolder(name: String) {
        val error = validateCustomFolderName(name)
        if (error != null) {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(getRootDir(), name)
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(requireContext(), R.string.gallery_folder_create_failed, Toast.LENGTH_SHORT).show()
            return
        }
        appSettingsManager.addCustomImageFolder(name)
        appSettingsManager.setSelectedOutputFolder(name)
        loadFolderCards()
    }

    private fun showCustomFolderActions(card: GalleryFolderCard) {
        if (card.isSystem || card.isVideo) return
        DialogUtils.builder(requireContext())
            .setTitle(card.title)
            .setItems(arrayOf(getString(R.string.gallery_folder_rename), getString(R.string.gallery_folder_delete))) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(card.folderName)
                    1 -> deleteCustomFolder(card.folderName)
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(oldName: String) {
        val input = EditText(requireContext())
        input.setText(oldName)
        DialogUtils.styleEditText(input)
        DialogUtils.ensureDialogLayoutParams(input)
        DialogUtils.builder(requireContext())
            .setTitle(R.string.gallery_folder_rename)
            .setView(input)
            .setPositiveButton(R.string.gallery_folder_rename_confirm) { _, _ ->
                val newName = input.text?.toString().orEmpty().trim()
                renameCustomFolder(oldName, newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameCustomFolder(oldName: String, newName: String) {
        if (oldName == newName) return
        val error = validateCustomFolderName(newName, oldName)
        if (error != null) {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            return
        }
        val rootDir = getRootDir()
        val oldDir = File(rootDir, oldName)
        val newDir = File(rootDir, newName)
        if (newDir.exists()) {
            Toast.makeText(requireContext(), R.string.gallery_folder_name_duplicated, Toast.LENGTH_SHORT).show()
            return
        }
        if (oldDir.exists() && !oldDir.renameTo(newDir)) {
            Toast.makeText(requireContext(), R.string.gallery_folder_rename_failed, Toast.LENGTH_SHORT).show()
            return
        }
        appSettingsManager.renameCustomImageFolder(oldName, newName)
        if (appSettingsManager.getSelectedOutputFolder().equals(oldName, ignoreCase = true)) {
            appSettingsManager.setSelectedOutputFolder(newName)
        }
        loadFolderCards()
    }

    private fun deleteCustomFolder(name: String) {
        val dir = File(getRootDir(), name)
        val isEmpty = !dir.exists() || dir.listFiles().isNullOrEmpty()
        if (!isEmpty) {
            Toast.makeText(requireContext(), R.string.gallery_folder_delete_non_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (dir.exists() && !dir.delete()) {
            Toast.makeText(requireContext(), R.string.gallery_folder_delete_failed, Toast.LENGTH_SHORT).show()
            return
        }
        appSettingsManager.removeCustomImageFolder(name)
        if (appSettingsManager.getSelectedOutputFolder().equals(name, ignoreCase = true)) {
            appSettingsManager.setSelectedOutputFolder(FolderModels.SAFE_NET_DIR)
        }
        loadFolderCards()
    }

    private fun validateCustomFolderName(name: String, ignoreName: String? = null): String? {
        if (name.isBlank()) return getString(R.string.gallery_folder_name_invalid)
        if (name.length > 32) return getString(R.string.gallery_folder_name_too_long)
        if (name.contains('/') || name.contains('\\')) return getString(R.string.gallery_folder_name_invalid)
        if (FolderModels.SYSTEM_DIRS.any { it.equals(name, ignoreCase = true) }) {
            return getString(R.string.gallery_folder_name_system_reserved)
        }
        val names = appSettingsManager.getCustomImageFolders() + FolderModels.SAFE_NET_DIR
        val duplicated = names.any {
            !it.equals(ignoreName, ignoreCase = true) && it.equals(name, ignoreCase = true)
        }
        if (duplicated) return getString(R.string.gallery_folder_name_duplicated)
        return null
    }

    private fun resolveFolderFiles(folderName: String, isVideo: Boolean): List<File> {
        val dir = File(getRootDir(), folderName)
        if (!dir.exists()) return emptyList()
        val extensions = if (isVideo) VIDEO_EXTENSIONS else IMAGE_EXTENSIONS
        return dir.listFiles { file ->
            file.isFile && file.extension.lowercase() in extensions
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun getRootDir(): File {
        return requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
    }

    private class FolderCardAdapter(
        private val thumbnailCacheManager: ThumbnailCacheManager,
        private val onOpen: (GalleryFolderCard) -> Unit,
        private val onSelect: (GalleryFolderCard) -> Unit,
        private val onLongPress: (GalleryFolderCard) -> Unit
    ) : RecyclerView.Adapter<FolderCardAdapter.FolderCardViewHolder>() {

        private var items: List<GalleryFolderCard> = emptyList()

        fun submitList(list: List<GalleryFolderCard>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderCardViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_folder_card, parent, false)
            return FolderCardViewHolder(view, thumbnailCacheManager)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: FolderCardViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
            holder.openButton.setOnClickListener { onOpen(item) }
            holder.selectIndicator.setOnClickListener { onSelect(item) }
            holder.itemView.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }

        class FolderCardViewHolder(
            itemView: View,
            private val thumbnailCacheManager: ThumbnailCacheManager
        ) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.folderTitle)
            private val count: TextView = itemView.findViewById(R.id.folderCount)
            private val preview1: ImageView = itemView.findViewById(R.id.folderPreview1)
            private val preview2: ImageView = itemView.findViewById(R.id.folderPreview2)
            val openButton: ImageButton = itemView.findViewById(R.id.openFolderButton)
            val selectIndicator: View = itemView.findViewById(R.id.folderSelectIndicator)
            private val selectDot: View = itemView.findViewById(R.id.folderSelectDot)

            fun bind(card: GalleryFolderCard) {
                title.text = card.title
                count.text = if (card.isVideo) {
                    itemView.context.getString(R.string.gallery_video_count, card.count)
                } else {
                    itemView.context.getString(R.string.gallery_image_count, card.count)
                }
                openButton.isEnabled = card.count > 0
                openButton.alpha = if (card.count > 0) 1f else 0.45f

                selectIndicator.visibility = if (card.selectable) View.VISIBLE else View.GONE
                selectDot.visibility = if (card.selected) View.VISIBLE else View.GONE
                selectIndicator.isEnabled = card.selectable
                selectIndicator.alpha = if (card.selectable) 1f else 0.45f

                bindPreview(preview1, card.previewFiles.getOrNull(0), card.isVideo)
                bindPreview(preview2, card.previewFiles.getOrNull(1), card.isVideo)
            }

            private fun bindPreview(view: ImageView, file: File?, isVideo: Boolean) {
                val placeholder = if (isVideo) android.R.drawable.ic_media_play else android.R.drawable.ic_menu_gallery
                view.setImageResource(placeholder)
                view.tag = file?.absolutePath
                if (file == null) return
                val kind = if (isVideo) ThumbnailCacheManager.MediaKind.VIDEO else ThumbnailCacheManager.MediaKind.IMAGE
                val expectedPath = file.absolutePath
                thumbnailCacheManager.load(file, kind, PREVIEW_SIZE) { bitmap ->
                    if (view.tag == expectedPath) {
                        if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageResource(placeholder)
                    }
                }
            }
        }
    }
}
