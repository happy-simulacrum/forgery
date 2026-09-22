plugins {
    alias(libs.plugins.forgery.android.library)
    alias(libs.plugins.forgery.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.forgery.app.core.network"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
