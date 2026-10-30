import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "org.hearth.ember"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.hearth.ember"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "0.9.1"
        // Where Ember looks for newer versions of itself.
        buildConfigField("String", "UPDATE_REPO", "\"modnite/ember-android\"")
    }

    // A release is signed with the key in keystore.properties (kept out of the repository, see README). Without it the release build is
    // left unsigned rather than signed with a throwaway key.
    val keystoreFile = rootProject.file("keystore.properties")
    val keystore = Properties().also { if (keystoreFile.exists()) keystoreFile.inputStream().use(it::load) }
    signingConfigs {
        if (keystoreFile.exists()) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    packaging {
        // BouncyCastle, JSch and others each ship the same OSGi manifest; one copy is enough.
        resources.excludes += setOf("META-INF/versions/*/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    buildTypes {
        // A debug build installs next to the real app (own name, own data) so testing never touches the app that is in daily use.
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = false
            // Phones only: no x86 libraries, which roughly halves the size of the app.
            ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
            // Without a release key the build is signed with the debug key so it installs and updates in place on the owner's phone.
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.livekit:livekit-android:2.23.0")
    implementation("androidx.core:core-telecom:1.0.0")
    implementation("com.google.crypto.tink:tink-android:1.15.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1") // Argon2id for the backup password (PasswordWrap)
    implementation("commons-net:commons-net:3.11.1") // FTP and FTPS backup places
    implementation("com.github.mwiede:jsch:0.2.20") // SFTP backup places
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
