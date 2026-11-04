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
        versionCode = 51
        versionName = "0.20.23"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // Private, permanent key supplied at build time from a GitHub Actions
    // secret. Never commit a signing key or publish a fresh debug key again.
    val stableKeystore = System.getenv("HALO_RELEASE_KEYSTORE_PATH")
    if (!stableKeystore.isNullOrBlank()) {
        signingConfigs {
            create("haloStable") {
                storeFile = file(stableKeystore)
                storePassword = System.getenv("HALO_RELEASE_PASSWORD")
                keyAlias = System.getenv("HALO_RELEASE_ALIAS") ?: "halo-release"
                keyPassword = System.getenv("HALO_RELEASE_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("haloStable")
                isMinifyEnabled = false
            }
        }
    }

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
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
