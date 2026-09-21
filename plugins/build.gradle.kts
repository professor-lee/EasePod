plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.ksp) }
android {
    namespace = "app.easepod.plugins"; compileSdk = 35
    defaultConfig { minSdk = 34; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":core")); api(project(":plugin-contract"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime); implementation(libs.androidx.room.ktx); ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}