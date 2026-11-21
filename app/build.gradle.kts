plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.anas.secretary"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.daybrief.android"
        minSdk = 26
        targetSdk = 34
        // GitHub passes the build number, so every build is newer than the last one
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
        versionCode = 100 + buildNumber
        versionName = "7.$buildNumber"
    }

    // public = the version anyone can download from github.com/AnasPotter/daybrief.
    // (The maintainer's own build adds a second flavor from personal.gradle, which isn't published.)
    flavorDimensions += "who"
    productFlavors {
        create("public") {
            dimension = "who"
            // The version people see (1.0.1 small update, 1.1 bigger) is picked when publishing;
            // versionCode still counts every published version (defaultConfig: 100 + buildNumber)
            val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
            versionName = (project.findProperty("publicVersion") as String?) ?: "1.$buildNumber"
            buildConfigField("boolean", "PUBLIC", "true")
            buildConfigField("String", "UPDATE_REPO", "\"AnasPotter/daybrief\"")
            buildConfigField("String", "DEFAULT_NAME", "\"\"")
        }
    }

    signingConfigs {
        // The public key lives outside the repo; GitHub gets it from secrets. Without it the release build is unsigned.
        val ks = System.getenv("PUBLIC_KEYSTORE")
        if (ks != null && file(ks).exists()) create("public") {
            storeFile = file(ks)
            storePassword = System.getenv("PUBLIC_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PUBLIC_KEY_ALIAS") ?: "secretary"
            keyPassword = System.getenv("PUBLIC_KEY_PASSWORD") ?: System.getenv("PUBLIC_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("public")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Image loading for link and screenshot previews
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Home screen widget
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Samsung Health data (sleep, steps, heart rate, workouts) through Health Connect, read on the phone.
    // alpha07 is the newest version that builds with compileSdk 34 (1.1.0 needs 36).
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    // Shizuku: lets "apps inside the panel" run the hidden screen as the shell user (no root)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

// The maintainer's own build (not published): extra flavor, its signing key and a library that can't be shared.
if (file("personal.gradle").exists()) apply(from = "personal.gradle")

