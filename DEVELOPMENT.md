# Safe Vision 开发文档

本文档用于帮助接手开发者快速了解项目结构、开发环境和常用模块位置。功能细节、版本规划和变更记录请放到独立更新文档中维护，避免入口文档过长。本文档需要同步更新。

## 项目简介

Safe Vision 是一个 Android 本地敏感内容检测与隐私遮挡应用，核心能力包括：

- 使用 ONNX YOLO 模型进行本地图片/视频检测
- 支持标准模型与动漫模型两套检测链路
- 支持图片、视频、文件夹批量处理
- 支持媒体库浏览、结果再编辑、导出与分享
- 支持屏幕实时检测与遮挡
- 支持多语言、主题、隐私预设与错误日志分享

## 开发环境

项目当前按轻量化 Android 工程维护，不依赖 Android Studio 作为唯一开发入口，可直接使用 Gradle 命令构建。

| 项目 | 要求 |
| --- | --- |
| JDK | 17 |
| Kotlin | 2.0.0+ |
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.10+ |
| compileSdk / targetSdk | 36 |
| minSdk | 24 |
| 包名 | `com.safe.vision` |

常用命令：

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew clean
```

关键配置文件：

- `settings.gradle.kts`：工程模块声明
- `build.gradle.kts`：根 Gradle 配置
- `app/build.gradle.kts`：应用模块配置、SDK 版本、依赖、签名、assets 来源
- `gradle.properties`：Gradle 全局属性
- `local.properties`：本机 Android SDK 路径

## 项目结构

```text
safe-app/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/safe/vision/
│       ├── res/
│       └── assets/
├── assets/
│   ├── 320n.onnx
│   ├── 320n-anime.onnx
│   ├── 320n-anime.onnx.data
│   ├── Default-stickers.png
│   └── support.jpg
├── dev_doc/
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

主要目录说明：

- `app/src/main/java/com/safe/vision/`：Kotlin 业务代码，当前主要为单包结构
- `app/src/main/res/layout/`：页面、弹窗和列表项布局
- `app/src/main/res/drawable/`：背景、按钮、卡片、图形资源
- `app/src/main/res/color/`：颜色选择器
- `app/src/main/res/values/`：默认资源、主题、颜色、简体中文字符串
- `app/src/main/res/values-b+zh+Hant/`：繁体中文字符串
- `app/src/main/res/values-en/`：英文字符串
- `app/src/main/res/values-ko/`：韩文字符串
- `app/src/main/res/xml/`：`FileProvider`、语言配置、无障碍服务等 XML 配置
- `assets/`：模型、默认贴纸和赞赏图片；通过 `app/build.gradle.kts` 挂载为应用 assets

## 代码定位

### 应用入口与导航

- `MainActivity.kt`：应用主 Activity、主界面初始化
- `ViewPagerAdapter.kt`：主 Tab / Fragment 绑定
- `ImageProcessingFragment.kt`：图片处理页，也是图片、视频、文件夹选择和屏幕检测入口
- `ImageGalleryFragment.kt`：媒体库首页
- `SettingsFragment.kt`：设置页

### 模型推理

- `YoloOnnxRunner.kt`：ONNX YOLO 推理、预处理、后处理
- `YoloModelProvider.kt`：模型 Runner 缓存与复用
- `DetectionModelVariant.kt`：标准模型 / 动漫模型变体定义
- `DetectionConfig.kt`：检测标签与阈值等配置
- `FaceLandmarkOnnxRunner.kt`：人脸关键点推理
- `FaceLandmarkModelProvider.kt`：人脸关键点模型缓存

### 图片、视频与批量处理

- `MediaSelectionHelper.kt`：图片/视频选择、URI 权限、系统入口适配
- `FolderMediaScanner.kt`：文件夹图片/视频扫描
- `BatchProcessingManager.kt`：批量任务调度
- `BatchProcessingService.kt`：批量处理前台服务
- `BatchResultsAdapter.kt`：批量结果列表
- `MediaSaveHelper.kt`：处理结果保存
- `VideoProcessingService.kt`：视频处理前台服务与队列入口
- `VideoProcessingManager.kt`：视频处理主流程
- `SafeVideoFrameDecoder.kt`：视频帧解码
- `VideoDetectionProcessor.kt`：视频帧检测、插值与遮挡处理
- `VideoCodecUtils.kt`：视频编码、音轨混流相关工具
- `VideoProcessingTrigger.kt`：视频处理触发来源定义

### 遮挡、隐私与渲染

- `DetectionRenderEngine.kt`：图片/视频共享遮挡渲染引擎
- `ImagePrivacyProcessor.kt`：媒体浏览时按检测元数据重新渲染遮挡
- `PrivacySettingsManager.kt`：隐私遮挡设置持久化
- `BlurEffects.kt`：马赛克、黑遮挡、高斯等基础效果
- `StickerLoader.kt`：贴纸加载与缓存
- `DetectionMetadataFormat.kt`：检测结果元数据格式
- `DetectionEditModels.kt`：检测框编辑数据模型与 JSON 读写
- `DetectionEditorOverlayView.kt`：浏览页检测框叠加绘制与交互

### 屏幕实时检测

- `ScreenDetectionService.kt`：屏幕检测前台服务
- `ScreenDetectionState.kt`：屏幕检测状态
- `ScreenAccessibilityOverlayService.kt`：无障碍遮挡服务
- `ScreenOverlayController.kt`：无障碍遮挡 / 普通悬浮窗遮挡调度
- `ScreenOverlayWindowHost.kt`：`WindowManager` 悬浮窗宿主
- `ScreenMaskOverlayView.kt`：屏幕遮挡绘制 View
- `ScreenPrivacyMaskRenderer.kt`：屏幕遮挡渲染逻辑
- `ScreenOverlayMode.kt`：屏幕遮挡模式定义

### 媒体库与浏览

- `ImageGalleryFragment.kt`：媒体库首页、文件夹卡片
- `GalleryFolderFullscreenFragment.kt`：文件夹全屏网格、多选、移动、删除、导出
- `ImageViewerFragment.kt`：图片/视频浏览、随机浏览、编辑入口
- `ThumbnailCacheManager.kt`：缩略图内存与磁盘缓存
- `FolderModels.kt`：文件夹类型、摘要和输出目录模型
- `OutputFolderAdapter.kt`：输出文件夹列表适配器
- `CircularCountdownView.kt`：随机播放倒计时指示器

### 设置、主题与语言

- `SettingsFragment.kt`：设置页 UI 与交互
- `AppSettingsManager.kt`：通用设置持久化
- `ThemeManager.kt`：主题应用与切换
- `AppLanguageManager.kt`：语言切换与系统语言映射
- `DialogUtils.kt`：统一弹窗入口与主题取色

### 日志与异常

- `DebugLogManager.kt`：调试日志写入与管理
- `CrashHandler.kt`：崩溃捕获
- `ErrorReportManager.kt`：错误报告生成、保存与分享

## 资源与输出路径

应用内主要输出目录：

- 有检测结果图片：当前选中的输出文件夹，通常为 `SafeNet` 或用户自建文件夹
- 无检测结果图片：`Android/data/com.safe.vision/files/no_detection/`
- 视频输出：`Android/data/com.safe.vision/files/SafeVideo/`
- 调试日志：`Android/data/com.safe.vision/files/logs/`
- 错误报告：`Android/data/com.safe.vision/files/logs/error_reports/`

系统导出目录：

- 图片导出：`Pictures/SafeVision/`
- 视频导出：`Movies/SafeVision/`

分享与导出相关配置：

- `app/src/main/res/xml/file_paths.xml`：`FileProvider` 路径配置
- `AndroidManifest.xml`：权限、Activity、Service、Provider、语言配置引用

## 开发注意事项

- 新增页面优先检查 `MainActivity.kt`、`ViewPagerAdapter.kt` 与对应 Fragment 的关系。
- 新增字符串时同步维护 `values`、`values-en`、`values-ko`、`values-b+zh+Hant`。
- 修改检测标签、模型输出或 JSON 字段时，同时检查推理、渲染、媒体浏览、编辑和随机筛选链路。
- 修改视频处理时注意前台服务、队列、进度通知和音轨混流。
- 修改屏幕检测时注意通知权限、录屏权限、无障碍 overlay 与普通悬浮窗 overlay 的差异。
- 修改文件读写或分享路径时同步检查 `file_paths.xml` 和 Android 存储权限兼容性。
- 目标 SDK 为 36，涉及 edge-to-edge、返回行为、后台服务和权限时需要按新系统行为验证。
