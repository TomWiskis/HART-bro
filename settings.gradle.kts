pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HART-bro"
include(":hart-core", ":transport-bluetooth", ":transport-usb", ":usb-serial", ":app")
project(":usb-serial").projectDir = file("third-party/usb-serial")
