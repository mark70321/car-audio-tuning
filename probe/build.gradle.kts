import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.marco.caraudio.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.marco.caraudio.probe"
        minSdk = 24
        // 刻意用 28：車機實際是 API 28，而且這樣在新手機上也能直接寫到「下載」資料夾
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // 只給自己的車機用，不上架
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
