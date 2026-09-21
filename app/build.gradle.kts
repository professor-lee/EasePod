import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync

plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }

// 发行签名由发行者自行提供：复制 keystore.properties.example 为 keystore.properties 并填入真实值。
// 仓库不保存任何私钥；keystore.properties 已被 .gitignore 忽略。
// 文件不存在时 release 仍可构建出未签名包。
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
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
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.media3.common)
    androidTestImplementation(libs.androidx.media3.exoplayer)
    androidTestImplementation(libs.androidx.media3.datasource.okhttp)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
}
