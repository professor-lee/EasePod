import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "app.easepod.data"
    compileSdk = 35
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("schemas")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.29.2" }
    generateProtoTasks { all().configureEach { builtins { id("java") { option("lite") } } } }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
