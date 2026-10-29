plugins {
    id("com.android.application")
}

android {
    namespace = "com.huawei.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aspiro.tidal"
        minSdk = 29
        targetSdk = 35
        versionCode = 99999      // <--- Un número muy alto para forzar la actualización
        versionName = "99.0"     // <--- Versión ficticia alta
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.media:media:1.7.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}