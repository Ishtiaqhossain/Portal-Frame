plugins {
    id("com.android.application") version "8.13.2"
}

android {
    namespace = "com.example.portalframe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.portalframe"
        minSdk = 28
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Build the existing in-place layout (no file moves yet) — Gradle Milestone 1.
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.setSrcDirs(listOf("src"))
        res.setSrcDirs(listOf("res"))
        assets.setSrcDirs(listOf("assets"))
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(files("libs/zxing-core-3.5.3.jar"))
}
