pluginManagement {
    repositories {
        maven {
            url = java.net.URI("https://dl.google.com/dl/android/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven {
            url = java.net.URI("https://dl.google.com/dl/android/maven2/")
        }
        mavenCentral()
    }
}

rootProject.name = "LinkdroidSignalizer"

include(":app")
