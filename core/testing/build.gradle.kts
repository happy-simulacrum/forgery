plugins {
    alias(libs.plugins.forgery.android.library)
}

android {
    namespace = "com.forgery.app.core.testing"
}

dependencies {
    api(libs.androidx.test.runner)
    api(libs.hilt.android.testing)
    implementation(libs.kotlinx.coroutines.test)
    implementation(libs.junit)
    implementation(libs.turbine)
    implementation(libs.androidx.test.ext)
}
