plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonebridge.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.phonebridge.android"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "0.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    buildFeatures {
        aidl = true // ICallTap: the Shizuku call line service
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.all { it.environment("PB_VECTOR", System.getenv("PB_VECTOR") ?: "") ; it.environment("PB_REPLY", System.getenv("PB_REPLY") ?: "") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.crypto.tink:tink-android:1.13.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Built-in ADB over wireless debugging (no Shizuku needed), the iTell way. CI resolves these.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Self-signed cert for ADB auth. bcprov (jdk15to18) already comes with libadb; match that family.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation("org.json:json:20240303")
}
