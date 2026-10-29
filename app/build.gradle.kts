import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Reads a value from the environment (CI secrets) or a Gradle property (~/.gradle/gradle.properties). */
fun secret(name: String): String? =
    providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull?.takeIf { it.isNotBlank() }

fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.teamomuito.larpfm"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.teamomuito.larpfm"
        minSdk = 26
        targetSdk = 35
        // Each CI build gets the next number, so every build installs over the last one.
        val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionCode = buildNumber
        // Bump by hand for a new version. Releases are titled with it ("larp.fm 2.0.0 (build 23)").
        versionName = "2.1.0"

        // Optional: bake in a Last.fm API account so users don't have to paste their own.
        buildConfigField("String", "LASTFM_API_KEY", secret("LASTFM_API_KEY").orEmpty().quoted())
        buildConfigField("String", "LASTFM_API_SECRET", secret("LASTFM_API_SECRET").orEmpty().quoted())
    }

    signingConfigs {
        val keystorePath = secret("SIGNING_STORE_FILE")
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = secret("SIGNING_STORE_PASSWORD")
                keyAlias = secret("SIGNING_KEY_ALIAS")
                keyPassword = secret("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore, sign with the debug key so the APK is still installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
