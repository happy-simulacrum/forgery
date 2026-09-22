plugins {
    alias(libs.plugins.forgery.android.library)
    alias(libs.plugins.forgery.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.forgery.app.core.datastore"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
}
