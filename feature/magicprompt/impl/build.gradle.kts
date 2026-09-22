plugins {
    alias(libs.plugins.forgery.android.feature)
    alias(libs.plugins.forgery.android.library.compose)
}

android {
    namespace = "com.forgery.app.feature.magicprompt.impl"
}

dependencies {
    api(projects.feature.magicprompt.api)

    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.designsystem)

    testImplementation(projects.core.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
