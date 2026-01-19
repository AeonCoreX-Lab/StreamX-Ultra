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
        // TorrentStream লাইব্রেরি এবং অন্যান্য GitHub লাইব্রেরির জন্য এটি জরুরি
        maven { url = uri("https://jitpack.io") } 
    }
}

rootProject.name = "StreamX Ultra"
include(":app")
