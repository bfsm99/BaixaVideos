import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Chave de assinatura fixa: localmente vem de keystore/keystore.properties, no GitHub dos Secrets.
// Tem de ser sempre a mesma, senão o Android recusa instalar as atualizações.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(name)

// No GitHub o VERSION_CODE vem do workflow e cresce a cada build
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 3

android {
    namespace = "com.baixavideos.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.baixavideos.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"
        // Repositório GitHub onde o app procura atualizações (vazio = sem atualização automática)
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            secret("SIGNING_KEYSTORE_PATH")?.let { storeFile = rootProject.file(it) }
            storePassword = secret("SIGNING_PASSWORD")
            keyAlias = "baixavideos"
            keyPassword = secret("SIGNING_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (secret("SIGNING_PASSWORD") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // O yt-dlp (Python) precisa das libs nativas extraídas no disco
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // yt-dlp para Android (suporta YouTube e +1000 sites)
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
