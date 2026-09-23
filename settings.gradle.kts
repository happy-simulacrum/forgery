pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Forgery"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// Core modules
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":core:ui")
include(":core:testing")
include(":core:datastore")
include(":core:database")
include(":core:network")
include(":core:data")

// Feature modules (full parity scope, SAAC removed, Comfy deferred to phase 5)
include(":feature:generate:api")
include(":feature:generate:impl")
include(":feature:inpaint:api")
include(":feature:inpaint:impl")
include(":feature:queue:api")
include(":feature:queue:impl")
include(":feature:gallery:api")
include(":feature:gallery:impl")
include(":feature:lora:api")
include(":feature:lora:impl")
include(":feature:modules:api")
include(":feature:modules:impl")
include(":feature:styles:api")
include(":feature:styles:impl")
include(":feature:magicprompt:api")
include(":feature:magicprompt:impl")
include(":feature:settings:api")
include(":feature:settings:impl")
include(":feature:power:api")
include(":feature:power:impl")
include(":feature:comfy:api")
include(":feature:comfy:impl")
include(":feature:analyze:api")
include(":feature:analyze:impl")
