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
        // FrostWire এর নিজস্ব রিপোজিটরি (এটি ছাড়া বিল্ড হবে না)
        maven { url = uri("https://dl.frostwire.com/maven") } 
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "StreamX Ultra"
include(":app")
