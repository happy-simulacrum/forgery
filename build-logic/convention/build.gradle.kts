plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "forgery.android.application"
            implementationClass = "ForgeryApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "forgery.android.library"
            implementationClass = "ForgeryLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "forgery.android.feature"
            implementationClass = "ForgeryFeatureConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "forgery.android.library.compose"
            implementationClass = "ForgeryLibraryComposeConventionPlugin"
        }
        register("hilt") {
            id = "forgery.hilt"
            implementationClass = "ForgeryHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "forgery.jvm.library"
            implementationClass = "ForgeryJvmLibraryConventionPlugin"
        }
    }
}
