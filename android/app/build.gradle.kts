import java.util.Properties

plugins {
    id("com.android.application")
}

/** 从仓库根的 local.properties 读取 SDK 与签名配置，避免把凭证写进构建脚本 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "top.imsyy.splayer_next.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "top.imsyy.splayer_next.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.2.1"
        resConfigs("zh", "en")
    }

    signingConfigs {
        create("release") {
            val ksPath = localProps.getProperty("ks.path") ?: ""
            if (ksPath.isNotEmpty()) {
                storeFile = file(ksPath)
                storePassword = localProps.getProperty("ks.pass")
                keyAlias = localProps.getProperty("ks.alias")
                keyPassword = localProps.getProperty("ks.pass")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (localProps.getProperty("ks.path") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources {
        noCompress += listOf("woff2", "woff", "ttf", "json", "bin", "krc", "ttml")
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-decoder:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
}
