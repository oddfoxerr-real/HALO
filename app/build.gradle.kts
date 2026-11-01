plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.oddfoxerr.halo"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.oddfoxerr.halo"
        minSdk = 29
        targetSdk = 36
        versionCode = 29
        versionName = "0.21.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        val updateKeyPath = System.getenv("HALO_UPDATE_KEYSTORE_PATH")
        val updatePassword = System.getenv("HALO_UPDATE_STORE_PASSWORD")
        if (!updateKeyPath.isNullOrBlank() && !updatePassword.isNullOrBlank()) {
            create("haloUpdate") {
                storeFile = file(updateKeyPath)
                storePassword = updatePassword
                keyAlias = "halo"
                keyPassword = updatePassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("haloUpdate")?.let { signingConfig = it }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
