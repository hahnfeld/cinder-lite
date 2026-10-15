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
        // Termux terminal-view / terminal-emulator — and nothing else may come from JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.termux.termux-app") } }
    }
}

rootProject.name = "cinder-lite"
include(":app")
