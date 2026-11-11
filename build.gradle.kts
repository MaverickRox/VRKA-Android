plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

