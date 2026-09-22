plugins {
    alias(libs.plugins.forgery.android.feature)
    alias(libs.plugins.forgery.android.library.compose)
}

android {
    namespace = "com.forgery.app.feature.comfy.impl"
}

dependencies {
    api(projects.feature.comfy.api)
    
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.designsystem)
}
