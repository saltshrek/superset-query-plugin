// Where plugins and dependencies come from: the public repositories, or,
// behind a firewall, a mirror. -PmavenRepositoryUrl=<url> is the mirror for
// dependencies, and for plugins too unless -PpluginRepositoryUrl=<url> names
// another. A mirror replaces the public repositories rather than adding to
// them, so nothing is fetched from the internet unnoticed.
pluginManagement {
    val mirror = providers.gradleProperty("pluginRepositoryUrl")
        .orElse(providers.gradleProperty("mavenRepositoryUrl"))
        .orNull
    repositories {
        if (mirror != null) maven(mirror) else gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Repositories are only declared here, so the mirror can't be bypassed.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val mirror = providers.gradleProperty("mavenRepositoryUrl").orNull
    repositories {
        if (mirror != null) maven(mirror) else mavenCentral()
    }
}

rootProject.name = "code-source"
