plugins {
    alias(libs.plugins.forgery.android.library)
    alias(libs.plugins.forgery.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.forgery.app.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.network)
    implementation(projects.core.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(projects.core.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.datastore)
}
