plugins {
    alias(libs.plugins.forgery.android.library)
    alias(libs.plugins.forgery.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.forgery.app.core.database"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
}
