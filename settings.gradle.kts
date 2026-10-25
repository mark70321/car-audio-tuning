pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "CarAudioStage0"
include(":app")          // 階段 0：系統級 EQ 測試 App（已完成，保留做紀錄）
include(":core-engine")  // 純 Kotlin 核心引擎：訊號產生、FFT、分析（正式版沿用）
include(":measure")      // 階段 1a：量測引擎驗證 App
include(":probe")    // 車機資訊：一鍵列出車機規格（給車機介面專案查資料用）
