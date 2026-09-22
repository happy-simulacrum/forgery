plugins {
    alias(libs.plugins.forgery.android.library)
    alias(libs.plugins.forgery.android.library.compose)
}

android {
    namespace = "com.forgery.app.core.designsystem"
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
