import java.util.zip.ZipFile
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync

plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }
android {
    namespace = "app.easepod"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.easepod"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/pluginMarketAssets"))
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}

val generateLicenseAssets by tasks.registering {
    val output = layout.buildDirectory.dir("generated/licenseAssets")
    val dependencies = configurations.getByName("releaseRuntimeClasspath").incoming.artifactView {
        componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
    }
    inputs.files(dependencies.files)
    outputs.dir(output)
    doLast {
        val artifacts = dependencies.artifacts.artifacts
        val text = buildString {
            appendLine("EasePod runtime dependency inventory")
            artifacts.sortedBy { it.id.componentIdentifier.displayName }.forEach { artifact ->
                appendLine("\n${artifact.id.componentIdentifier.displayName}")
                if (artifact.file.isFile && artifact.file.extension in setOf("jar", "aar")) ZipFile(artifact.file).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('/').matches(Regex("(?i)(license|notice|copying)([._-].*)?")) }.forEach { entry ->
                        appendLine(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                    }
                }
            }
        }
        output.get().asFile.apply { mkdirs() }.resolve("DEPENDENCIES.txt").writeText(text)
    }
}

// Keep the first local plugin market self-contained: the host APK carries the
// independently installable Netease plugin as an asset.  It is still verified
// and installed through the normal Android package installer at runtime.
val generatePluginMarketAssets by tasks.registering(Copy::class) {
    val pluginProject = project(":netease-plugin")
    dependsOn(pluginProject.tasks.named("assembleDebug"))
    from(pluginProject.layout.buildDirectory.file("outputs/apk/debug/netease-plugin-debug.apk"))
    into(layout.buildDirectory.dir("generated/pluginMarketAssets/plugins"))
    rename { "easepod-netease-plugin-0.1.0-debug.apk" }
}

// Theme packages are immutable data archives, but they still need to be
// carried inside the host APK so the offline market is useful on a fresh
// device.  Keep the archive under the same assets/plugins namespace used by
// the market UI and pass it through ThemeManager/PendingInstallStore at run
// time; this does not auto-install or expose it as an Android application.
val generateThemeMarketAssets by tasks.registering(Sync::class) {
    from(rootProject.file("plugins/market/easepod-ink-screen-1.0.2.ep-theme"))
    into(layout.buildDirectory.dir("generated/pluginMarketAssets/plugins"))
}

tasks.matching { it.name.endsWith("Assets") && it.name.startsWith("merge") || it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(generateLicenseAssets, generatePluginMarketAssets, generateThemeMarketAssets) }
dependencies {
    implementation(project(":core")); implementation(project(":data")); implementation(project(":playback")); implementation(project(":plugins"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-runtime:2.6.1")
    androidTestImplementation("androidx.media3:media3-common:1.5.1")
    androidTestImplementation("androidx.media3:media3-exoplayer:1.5.1")
    androidTestImplementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
