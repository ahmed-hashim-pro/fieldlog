pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fieldlog"

include(":core", ":cli")
// The Android app needs the Android SDK; leave it out with -Pjvm-only.
if (!providers.gradleProperty("jvm-only").isPresent) include(":app")
