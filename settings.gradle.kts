// Table of contents for the whole Gradle build.
// - Names the project and lists which modules exist.
// - Declares the repositories Gradle downloads plugins and libraries from.
// - Wires up the version catalog (gradle/libs.versions.toml) so modules can
//   reference dependencies by name instead of hard-coded version strings.

// Explicit import so the IDE resolves RepositoriesMode (below). The CLI build
// works without it — this just silences the editor's red underline.
import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
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
    id("org.gradle.toolchains.foojay-resolver-convention").version("0.10.0")
}

dependencyResolutionManagement {
    // Fail the build if a module declares its own repositories — keep them all here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScrollKiller"
include(":app")
