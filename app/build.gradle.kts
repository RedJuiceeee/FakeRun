import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val releaseProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
android {
    namespace = "com.fakerun.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.fakerun.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }
    signingConfigs {
        if (releaseProperties.isNotEmpty()) {
            create("release") {
                fun requiredValue(name: String): String = releaseProperties.getProperty(name)
                    ?.takeIf { it.isNotBlank() } ?: error("signing.properties 缺少 $name")
                storeFile = rootProject.file(requiredValue("storeFile"))
                storePassword = requiredValue("storePassword")
                keyAlias = requiredValue("keyAlias")
                keyPassword = requiredValue("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
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
