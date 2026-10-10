import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
android {
    namespace = "com.fakerun.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.fakerun.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        manifestPlaceholders["BAIDU_MAP_AK"] = localConfig.getProperty("BAIDU_MAP_AK", "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("test") {
            java.setSrcDirs(listOf(rootProject.file("tests/unit")))
            resources.setSrcDirs(listOf(rootProject.file("tests/fixtures")))
        }
        getByName("androidTest") {
            java.setSrcDirs(listOf(rootProject.file("tests/device")))
            resources.setSrcDirs(listOf(rootProject.file("tests/fixtures")))
        }
    }
    packaging { jniLibs.useLegacyPackaging = false }
}
dependencies {
    implementation("com.baidu.lbsyun:BaiduMapSDK_Map:8.2.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
