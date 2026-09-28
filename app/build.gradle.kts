plugins {
    id("com.android.application")
}

android {
    namespace = "com.hermes.clustermirror"
    compileSdk = 28

    defaultConfig {
        applicationId = "com.hermes.clustermirror"
        minSdk = 28          // 车机是 Android 9 = API 28
        targetSdk = 28       // 不要调高，车机系统版本就是这个
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 用 debug 签名，方便直接安装测试（车机装正式签名包会被拦）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // 必须用 Java 8：compileSdk 28 时 AGP 不允许 Java 9+ 源码
        // （报 "In order to compile Java 9+ source, please set compileSdkVersion to 30 or above"）
        // 车机是 Android 9，Java 8 字节码正好
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 纯 framework API（Android 9 / API 28），不需要任何第三方依赖
}
