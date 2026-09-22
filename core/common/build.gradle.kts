plugins {
    alias(libs.plugins.forgery.android.library)
    alias(libs.plugins.forgery.hilt)
}

android {
    namespace = "com.forgery.app.core.common"
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
