import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile = providers.environmentVariable("VRKA_SIGNING_PROPERTIES")
    .map { file(it) }
    .orElse(providers.provider {
        val localFile = rootProject.file("signing.properties")
        val userHome = System.getProperty("user.home")
        val userHomeFile = if (userHome != null) file("$userHome/.vrka-android-signing/signing.properties") else null
        when {
            localFile.isFile -> localFile
            userHomeFile?.isFile == true -> userHomeFile
            else -> null
        }
    })
    .orNull
val signingProperties = signingPropertiesFile?.takeIf { it.isFile }?.inputStream()?.use { input ->
    Properties().apply { load(input) }
}

extensions.configure<ApplicationExtension> {
    namespace = "com.mvrk.vrka"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mvrk.vrka"
        minSdk {
            version = release(26)
        }
        targetSdk {
            version = release(36)
        }
        versionCode = 40001
        versionName = "4.0.1"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    if (signingProperties != null) {
        signingConfigs {
            create("release") {
                val storeFilePath = signingProperties.getProperty("storeFile")
                val storeCandidate = file(storeFilePath)
                storeFile = if (storeCandidate.isAbsolute) {
                    storeCandidate
                } else {
                    signingPropertiesFile?.parentFile?.resolve(storeFilePath) ?: storeCandidate
                }
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

abstract class VerifyReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val hasCredentials: Property<Boolean>

    @TaskAction
    fun verify() {
        if (!hasCredentials.get()) {
            throw GradleException(
                "Release signing credentials are missing. Release builds require explicit signing credentials.\n" +
                "Please configure VRKA_SIGNING_PROPERTIES environment variable pointing to your signing.properties file,\n" +
                "or place signing.properties in ~/.vrka-android-signing/signing.properties or the project root.\n" +
                "The file must specify storeFile, storePassword, keyAlias, and keyPassword."
            )
        }
    }
}

val hasReleaseSigning = signingProperties != null
val verifyReleaseSigning = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
    hasCredentials.set(hasReleaseSigning)
}

tasks.matching { it.name in listOf("validateSigningRelease", "packageRelease") }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:153.0.20260810162159")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

