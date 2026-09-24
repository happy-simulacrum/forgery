plugins {
    alias(libs.plugins.forgery.android.application)
    alias(libs.plugins.forgery.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.forgery.app.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forgery.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "2.5"
        testInstrumentationRunner = "com.forgery.app.core.testing.ForgeryTestRunner"
    }

    buildFeatures { compose = true }
    composeOptions { }

    // Release signing via sibling keystore; pass -PFORGERY_KEYSTORE_FILE=... (abs path),
    // -PFORGERY_KEYSTORE_PASSWORD=... [-PFORGERY_KEY_PASSWORD=...] or env vars of the same
    // names. Unsigned release when absent (never commit secrets here).
    val releaseKeystoreFile: String =
        (project.findProperty("FORGERY_KEYSTORE_FILE") as? String
            ?: System.getenv("FORGERY_KEYSTORE_FILE") ?: "").trim()
    val releaseKeystorePassword: String =
        (project.findProperty("FORGERY_KEYSTORE_PASSWORD") as? String
            ?: System.getenv("FORGERY_KEYSTORE_PASSWORD") ?: "").trim()
    val releaseKeyPassword: String =
        (project.findProperty("FORGERY_KEY_PASSWORD") as? String
            ?: System.getenv("FORGERY_KEY_PASSWORD")
            ?: releaseKeystorePassword).trim()
    val hasReleaseKeystore: Boolean =
        releaseKeystoreFile.isNotBlank() && releaseKeystorePassword.isNotBlank() &&
            rootProject.file(releaseKeystoreFile).exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreFile)
                storePassword = releaseKeystorePassword
                keyAlias = "google_play_key"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    implementation(projects.feature.generate.impl)
    implementation(projects.feature.inpaint.impl)
    implementation(projects.feature.queue.impl)
    implementation(projects.feature.gallery.impl)
    implementation(projects.feature.lora.impl)
    implementation(projects.feature.modules.impl)
    implementation(projects.feature.styles.impl)
    implementation(projects.feature.settings.impl)
    implementation(projects.feature.comfy.impl)
    implementation(projects.feature.analyze.impl)

    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.network)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(projects.core.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
