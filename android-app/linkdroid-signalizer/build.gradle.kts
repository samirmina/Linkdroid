buildscript {
    repositories {
        maven {
            url = java.net.URI("https://dl.google.com/dl/android/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.6.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
    }
}
