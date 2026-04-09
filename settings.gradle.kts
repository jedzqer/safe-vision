pluginManagement {
    repositories {
        google()            // <-- 必须！否则 AGP 无法解析
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()            // <-- 必须！否则 Android SDK 依赖下载不到
        mavenCentral()
    }
}

rootProject.name = "SafeVision"
include(":app")
