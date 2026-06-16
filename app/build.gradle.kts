import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
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
        versionCode = 6
        versionName = "1.1.4"
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
            isMinifyEnabled = false
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
    // QR decoders, tried in a cascade (see PhotosActivity): ML Kit's bundled (on-device, no GMS)
    // model is the strongest on bright/washed-out screens; BoofCV is the open-source fallback if
    // ML Kit can't initialise on a GMS-less Portal; the vendored ZXing jar is the final fallback.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // boofcv-recognition carries FactoryFiducial/QR detection (pure-JVM); we feed it the NV21 luma
    // directly, so we don't need the boofcv-android Bitmap/NV21 helpers.
    implementation("org.boofcv:boofcv-recognition:1.1.4")
    implementation(files("libs/zxing-core-3.5.3.jar"))

    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.12.4")
}
