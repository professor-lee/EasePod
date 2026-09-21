import com.google.protobuf.gradle.id

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
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
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-extractor:1.5.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore:1.1.2")
    implementation("com.google.protobuf:protobuf-javalite:4.29.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
