import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Version : une seule ligne dans version.properties (comme l'appli TV). Le numéro interne exigé par Android
// (versionCode, toujours croissant) en est déduit : 1.2.3 -> 10203.
val appVersion: String = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("version").trim()
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    major * 10000 + minor * 100 + patch
}

// Clé de signature des versions publiées : hors du dépôt (voir README, « Clé de signature »)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.pauljean445.iptvtel"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pauljean445.iptvtel"
        // Android 10 minimum : dossier Films/ partagé sans autorisation (MediaStore, fichiers « en cours »)
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode  // version.properties
        versionName = appVersion
        // VLC (diffusion) en ARM 64 bits seulement : tous les téléphones récents (le S25 FE de l'utilisateur)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Version publiée : code optimisé et allégé (R8), fluide et le plus petit possible
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
        resources.excludes += listOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}


dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")  // seules les icônes utilisées restent (R8)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core:1.13.1")  // FileProvider : APK de mise à jour confié à l'installeur
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Lecteur intégré d'Android (Media3) : quelques Mo au lieu des ~30 Mo de VLC ; décodeurs du téléphone
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Diffusion vers un Chromecast / une TV « Chromecast intégré » : VLC, comme l'appli PC (son converti à la volée,
    // flux continu : la télé ne recharge rien à chaque déplacement). ~40 Mo : choix de l'utilisateur (réactivité)
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
