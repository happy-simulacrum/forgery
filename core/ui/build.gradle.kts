plugins {
    alias(libs.plugins.forgery.android.library)
    alias(libs.plugins.forgery.android.library.compose)
}

android {
    namespace = "com.forgery.app.core.ui"
}

dependencies {
    api(projects.core.model)
    api(projects.core.designsystem)
    api(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}
