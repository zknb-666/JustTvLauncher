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
        multiDexEnabled = true  // 启用 MultiDex 支持 (方法数超过 65536)
//        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        viewBinding = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
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
//    implementation("androidx.core:core-ktx:1.6.0")
//    implementation("androidx.appcompat:appcompat:1.3.1")
//    implementation("com.google.android.material:material:1.4.0")
//    implementation("androidx.constraintlayout:constraintlayout:2.1.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
    
    // MultiDex support for API < 21 (方法数超过 65536 时需要)
    implementation("androidx.multidex:multidex:2.0.1")
    
    // OkHttp for modern HTTP/HTTPS support
    implementation("com.squareup.okhttp3:okhttp:3.12.13") // 兼容Android 4.2的最后版本
    
    // BouncyCastle for modern TLS support on older Android versions (纯Java实现，无native依赖)
    implementation("org.bouncycastle:bcprov-jdk15on:1.68") // 纯Java实现，适合system/app
    implementation("org.bouncycastle:bctls-jdk15on:1.68") // TLS支持
    
    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.8.9")
    
//    testImplementation("junit:junit:4.13.2")
//    androidTestImplementation("androidx.test.ext:junit:1.1.3")
//    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}