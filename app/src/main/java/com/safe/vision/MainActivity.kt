package com.safe.vision

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        const val SHARE_IMAGES_REQUEST_KEY = "share_images_request"
        const val SHARE_IMAGES_BUNDLE_KEY = "uris"
        const val IMPORT_PRESET_REQUEST_KEY = "import_preset_request"
        const val IMPORT_PRESET_BUNDLE_KEY = "uris"
    }

    private enum class SharedContentKind {
        IMAGES,
        PRESET_PACKAGES,
        UNSUPPORTED
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var adapter: ViewPagerAdapter

    override fun attachBaseContext(newBase: Context) {
        val appSettings = AppSettingsManager.getInstance(newBase)
        val theme = appSettings.getAppTheme()
        val wrapped = ThemeManager.wrapContextWithCustomColors(newBase, theme, appSettings.getCustomPalette())
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyToApp(this)
        val appTheme = AppSettingsManager.getInstance(this).getAppTheme()
        ThemeManager.applyTheme(this, appTheme)
        super.onCreate(savedInstanceState)
        
        // 启用边到边显示模式（Android 15+ 必需）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // 初始化崩溃处理器
        CrashHandler.init(this)
        setContentView(R.layout.activity_main_with_tabs)
        
        // 处理系统栏插入，确保内容不被状态栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        // 设置适配器
        adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter
        // 禁止左右滑动切换标签页，仅允许点击顶部 Tab 切换
        viewPager.isUserInputEnabled = false
        // 首帧后预热全部标签页，避免首次进入设置页时再同步创建复杂 UI。
        viewPager.post {
            viewPager.offscreenPageLimit = adapter.itemCount - 1
        }

        // 连接TabLayout和ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                ViewPagerAdapter.IMAGE_PROCESSING_POSITION -> getString(R.string.menu_image_processing)
                ViewPagerAdapter.IMAGE_GALLERY_POSITION -> getString(R.string.menu_image_gallery)
                ViewPagerAdapter.IMAGE_VIEWER_POSITION -> getString(R.string.menu_image_viewer)
                ViewPagerAdapter.SETTINGS_POSITION -> getString(R.string.menu_settings)
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }.attach()

        handleIncomingShareIntent(intent)
        maybeShowFirstLaunchDialog()
        lifecycleScope.launch(Dispatchers.IO) {
            MetadataRepairManager.getInstance(applicationContext).repairIfNeeded()
        }
        window.decorView.post {
            ErrorReportManager.maybeShowPendingCrashDialog(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // 进入后台或页面切换时尽快落盘，减少日志延迟与丢失风险
        DebugLogManager.onAppBackgrounded()
    }

    override fun onDestroy() {
        // 退出当前Activity前再触发一次强制落盘
        DebugLogManager.flushNow()
        super.onDestroy()
    }

    // 公共方法，用于从Fragment跳转到媒体浏览页面
    fun openImageViewer(imageFile: File) {
        // 先投递目标媒体路径，再切换到媒体浏览页面，避免未初始化时丢失目标
        supportFragmentManager.setFragmentResult(
            "open_media_request",
            bundleOf("path" to imageFile.absolutePath)
        )
        viewPager.setCurrentItem(ViewPagerAdapter.IMAGE_VIEWER_POSITION, true)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val uris = extractSharedUris(intent)
        if (uris.isEmpty()) return

        when (resolveSharedContentKind(intent, uris)) {
            SharedContentKind.IMAGES -> {
                supportFragmentManager.setFragmentResult(
                    SHARE_IMAGES_REQUEST_KEY,
                    bundleOf(SHARE_IMAGES_BUNDLE_KEY to ArrayList(uris))
                )
                viewPager.setCurrentItem(ViewPagerAdapter.IMAGE_PROCESSING_POSITION, false)
                clearHandledShareIntent(intent)
            }
            SharedContentKind.PRESET_PACKAGES -> {
                supportFragmentManager.setFragmentResult(
                    IMPORT_PRESET_REQUEST_KEY,
                    bundleOf(IMPORT_PRESET_BUNDLE_KEY to ArrayList(uris))
                )
                viewPager.setCurrentItem(ViewPagerAdapter.SETTINGS_POSITION, false)
                clearHandledShareIntent(intent)
            }
            SharedContentKind.UNSUPPORTED -> Unit
        }
    }

    private fun extractSharedUris(intent: Intent): List<Uri> {
        val uris = linkedSetOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                uris.add(uri)
            }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val shared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (!shared.isNullOrEmpty()) {
                uris.addAll(shared)
            }
        }
        intent.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(uris::add)
            }
        }
        return uris.toList()
    }

    private fun resolveSharedContentKind(intent: Intent, uris: List<Uri>): SharedContentKind {
        val declaredType = intent.type?.lowercase(Locale.ROOT)
        if (declaredType?.startsWith("image/") == true) return SharedContentKind.IMAGES
        if (isPresetMimeType(declaredType)) return SharedContentKind.PRESET_PACKAGES

        val detectedKinds = uris.map(::classifySharedUri).toSet() - SharedContentKind.UNSUPPORTED
        if (detectedKinds.size != 1) return SharedContentKind.UNSUPPORTED
        return detectedKinds.first()
    }

    private fun classifySharedUri(uri: Uri): SharedContentKind {
        val contentType = contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        if (contentType?.startsWith("image/") == true) return SharedContentKind.IMAGES
        if (isPresetMimeType(contentType)) return SharedContentKind.PRESET_PACKAGES

        val displayName = resolveDisplayName(uri)?.lowercase(Locale.ROOT).orEmpty()
        return when {
            displayName.endsWith(".zip") -> SharedContentKind.PRESET_PACKAGES
            else -> SharedContentKind.UNSUPPORTED
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun isPresetMimeType(mimeType: String?): Boolean {
        return mimeType == "application/zip" || mimeType == "application/x-zip-compressed"
    }

    private fun clearHandledShareIntent(intent: Intent) {
        intent.action = null
        intent.clipData = null
        intent.data = null
        intent.removeExtra(Intent.EXTRA_STREAM)
    }

    private fun maybeShowFirstLaunchDialog() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("welcome_shown", false)) return

        val messageView = android.widget.TextView(this).apply {
            text = getString(R.string.first_launch_message)
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setPadding(48, 32, 48, 0)
            textSize = 16f
        }
        DialogUtils.ensureDialogLayoutParams(messageView)

        DialogUtils.builder(this)
            .setTitle(getString(R.string.first_launch_title))
            .setView(messageView)
            .setPositiveButton(getString(R.string.first_launch_confirm)) { dialog, _ ->
                prefs.edit().putBoolean("welcome_shown", true).apply()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}
