plugins {
    id("com.android.application")
}

android {
    namespace = "com.pycharm4android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pycharm4android"
        minSdk = 26
        // 必须 <=28：Android 10+ 仅允许 targetSdk<29 的应用执行私有目录中的二进制（proot / rootfs 内程序）
        targetSdk = 28
        versionCode = 27
        versionName = "1.11.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += listOf("gz", "xz", "so")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*")
    }
}
