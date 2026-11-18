plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    /**
     * Personal opinion:
     * compileSdk doesn't make sense,
     * just generally be consistent with targetSdk.
     */
    compileSdk = 32
    defaultConfig {
        applicationId = "cf.zknb.tvlauncher"
        minSdk = 17
        targetSdk = 32
        versionCode = 20210913
        versionName = "2021.9.13"
        // 移除 BouncyCastle / OkHttp / Gson 后方法数远低于 65536，
        // 不再需要 MultiDex（省掉第二个 dex 和老设备上的启动安装开销）
        ndk {
            // 目标设备是 armeabi-v7a 电视盒子；只带这一个 ABI 可省 ~3 MB。
            // 其它 ABI 上 Conscrypt 加载不到 .so 时会自动回退系统 TLS，不会崩溃。
            abiFilters.add("armeabi-v7a")
        }
    }
    buildFeatures {
        viewBinding = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // 只保留 UI / 协程 / 生命周期 + 高性能 TLS 依赖。
    // 网络(OkHttp)、JSON(Gson)、MultiDex 已由平台 API 取代；
    // BouncyCastle 的纯 Java TLS 由 Conscrypt(native BoringSSL) 取代。
    // 详见 util/HttpCompat.kt。
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
    // 高性能 TLS：BoringSSL native JSSE，替代 BouncyCastle 的纯 Java 实现。
    // 支持 API 9+，为 Android 4.x 提供现代 cipher suite / TLS 1.2~1.3 / SNI。
    implementation("org.conscrypt:conscrypt-android:2.5.2")

//    testImplementation("junit:junit:4.13.2")
//    androidTestImplementation("androidx.test.ext:junit:1.1.3")
//    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}
