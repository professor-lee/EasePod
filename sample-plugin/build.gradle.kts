plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }
android {
    namespace = "app.easepod.sampleplugin"; compileSdk = 35
    defaultConfig { applicationId = "app.easepod.sampleplugin"; minSdk = 34; targetSdk = 35; versionCode = 1; versionName = "1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":plugin-contract"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}