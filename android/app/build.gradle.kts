plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

// Release signing comes from environment variables set by CI (GitHub Secrets). No secret, no release key.
// The keystore file is decoded by the workflow to KEYSTORE_PATH; passwords never appear in this file.
val releaseKeystore: File? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { File(it) }
val hasReleaseKey = releaseKeystore?.exists() == true

android {
    namespace = "com.fintracks.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "bd.shohagvai.fintrack"
        minSdk = 26
        targetSdk = 35
        // CI sets VERSION_CODE to the Actions run number, so every CI build is newer than the last one.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.1.0"

        // Public values only. The publishable key is safe to ship; RLS protects the data.
        buildConfigField("String", "SUPABASE_URL", "\"https://ennkvjmxyjztjszmfaag.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_ZEs8TSPiUzrt8gKmlmOjDw_kkoKr8_h\"")
        // Set -PgoogleWebClientId=... in ~/.gradle/gradle.properties to enable Google sign-in.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${project.findProperty("googleWebClientId") ?: ""}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "pkcs12"
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // QR codes for receipts (free, Apache-2.0)
    implementation("com.google.zxing:core:3.5.3")
    // On-device receipt OCR (free, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Unit tests for parsers and backup round-trip (JVM, no device needed)
    // Firebase Cloud Messaging (push). Versions are pinned by the BoM.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Google sign-in (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
