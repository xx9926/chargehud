plugins {
    id("com.android.application")
}

android {
    namespace = "com.chargehud.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chargehud.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 还没有正式的发布密钥库，先用 debug 密钥签，产物才能直接装机验证。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // release 构建会连带跑 lintVital，而离线机器的 Gradle 缓存里没有 lint-gradle 依赖，
        // 会让整个 assembleRelease 失败；关掉它，检查交给 debug 构建和手动跑 lint。
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
