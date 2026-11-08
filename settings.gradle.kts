pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Chogumi"
include(":app")
include(":baselineprofile")
include(":core:foundation")
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:data")
include(":core:ui")
include(":core:glass")
include(":core:reader")
include(":core:res")
include(":feature:home")
include(":feature:progress")
include(":feature:login")
include(":feature:subjectdetails")
include(":feature:explore")
include(":feature:timeline")
include(":feature:community")
include(":feature:profile")
include(":feature:calendar")
include(":feature:settings")
include(":feature:monodetails")
include(":feature:messages")
