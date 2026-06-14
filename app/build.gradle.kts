plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

android {
    namespace = "com.portalhacks.frame"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.portalhacks.frame"
        minSdk = 28
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
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

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
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
