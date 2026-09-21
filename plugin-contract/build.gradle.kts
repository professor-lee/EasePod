plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.parcelize) }
android {
    namespace = "app.easepod.contract"; compileSdk = 35
    defaultConfig { minSdk = 34 }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { api(libs.kotlinx.coroutines.android) }