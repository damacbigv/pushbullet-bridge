import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val envProps = Properties()
val rootEnv = rootProject.file("../.env")
if (rootEnv.exists()) {
    rootEnv.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
            val key = trimmed.substringBefore("=").trim()
            val value = trimmed.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
            envProps[key] = value
        }
    }
}

fun getEnvConfig(key: String, defaultVal: String = ""): String {
    return System.getenv(key) ?: envProps.getProperty(key, defaultVal)
}

android {
    namespace = "com.example.pushbridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.pushbridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 303
        versionName = "3.0.3"

        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${getEnvConfig("FIREBASE_PROJECT_ID", "pushclonebridge")}\"")
        buildConfigField("String", "FIREBASE_RTDB_URL", "\"${getEnvConfig("FIREBASE_DATABASE_URL", "https://pushclonebridge-default-rtdb.firebaseio.com")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${getEnvConfig("FIREBASE_API_KEY", "")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${getEnvConfig("FIREBASE_APP_ID", "1:250338142494:web:5029dbee8c1e9da606810f")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${getEnvConfig("FIREBASE_MESSAGING_SENDER_ID", "250338142494")}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${getEnvConfig("FIREBASE_STORAGE_BUCKET", "pushclonebridge.firebasestorage.app")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Firebase
  val firebaseBom = platform("com.google.firebase:firebase-bom:33.9.0")
  implementation(firebaseBom)
  implementation("com.google.firebase:firebase-database-ktx")
  implementation("com.google.firebase:firebase-messaging-ktx")
}
