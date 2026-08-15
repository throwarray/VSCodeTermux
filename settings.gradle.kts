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
        maven("https://jitpack.io") {
            // Scope this repo to just the group it's actually needed for —
            // faster resolution (Gradle won't probe it for every other
            // dependency) and avoids any ambiguity with same-named
            // artifacts elsewhere. Real coordinates are
            // com.github.termux.termux-app:{terminal-emulator,terminal-view}
            // — terminal-emulator/terminal-view are subprojects of the
            // termux-app monorepo, not their own top-level repos, so
            // JitPack's multi-module group convention (com.github.<user>.<repo>)
            // applies, not the plain com.github.<user> single-repo form.
            content { includeGroup("com.github.termux.termux-app") }
        }
    }
}

rootProject.name = "VSCodeTermux"
include(":app")
