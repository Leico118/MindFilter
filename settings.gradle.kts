pluginManagement {
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
        // Tesseract (lecture du texte dans les images), compilé pour Android.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MindFilter"
include(":app")
