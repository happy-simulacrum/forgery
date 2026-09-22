plugins {
    alias(libs.plugins.forgery.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.forgery.app.feature.generate.api"
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation.compose)
}
