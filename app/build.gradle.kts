import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing inputs, read from env vars (CI) or a local, git-ignored
// keystore.properties (local release builds). Nothing secret is committed.
// See RELEASING.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun releaseSecret(env: String, prop: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "com.portalhacks.frame"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.portalhacks.frame"
        minSdk = 28
        targetSdk = 29
        versionCode = 10
        versionName = "1.1.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Build the existing in-place layout (no file moves during the migration).
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.setSrcDirs(listOf("src"))
        kotlin.setSrcDirs(listOf("src"))
        res.setSrcDirs(listOf("res"))
        assets.setSrcDirs(listOf("assets"))
    }

    signingConfigs {
        create("release") {
            val ksPath = releaseSecret("FRAME_KEYSTORE", "storeFile")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = releaseSecret("FRAME_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = releaseSecret("FRAME_KEY_ALIAS", "keyAlias")
                keyPassword = releaseSecret("FRAME_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8: shrink + obfuscate the code and strip unused resources for the release APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign the release only when a keystore is configured; otherwise
            // assembleRelease still builds, producing an unsigned APK.
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // The app deliberately targets API 29 for the Meta Portal Go and is
        // sideloaded (not shipped via Google Play), so Play's "target a recent API
        // level" requirement doesn't apply. Don't fail the release build on it.
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files("libs/zxing-core-3.5.3.jar"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
}
