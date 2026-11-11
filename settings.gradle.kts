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
        // Production and CI resolve only from these trusted, explicitly scoped repositories.
        // mavenLocal is intentionally absent: an unrestricted local repository could shadow
        // official artifacts with matching coordinates. Development builds that genuinely need
        // a local artifact must add a content-filtered repository explicitly in a local change.
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.mozilla.org/maven2/")
            content {
                includeGroupByRegex("org\\.mozilla\\.geckoview(\\..*)?")
            }
        }
    }
}

rootProject.name = "VRKA-Android"
include(":app")

