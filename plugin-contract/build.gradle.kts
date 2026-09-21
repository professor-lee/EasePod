plugins { id("com.android.library"); kotlin("android"); id("org.jetbrains.kotlin.plugin.parcelize") }
android {
    namespace = "app.easepod.contract"; compileSdk = 35
    defaultConfig { minSdk = 34 }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0") }
