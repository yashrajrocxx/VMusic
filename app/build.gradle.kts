import java.util.Properties
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    val appId = "com.dexy.vmusic.app"

    namespace = "app.pulse.android"
    compileSdk = 37

    defaultConfig {
        applicationId = appId

        minSdk = 24
        targetSdk = 36

        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = project.version.toString()

        multiDexEnabled = true
    }

    val keystorePropsFile = sequenceOf(
        file("${System.getProperty("user.home")}/Documents/android-keys/keystore.properties"),
        file("${System.getProperty("user.home")}/android_keys/keystore.properties"),
        rootProject.file("keystore.properties"),
        file("keystore.properties")
    ).firstOrNull { it.exists() }

    val keystoreProperties = Properties().apply {
        if (keystorePropsFile != null) {
            load(keystorePropsFile.inputStream())
        }
    }

    signingConfigs {
        create("ci") {
            storeFile = System.getenv("ANDROID_NIGHTLY_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("ANDROID_NIGHTLY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_NIGHTLY_KEYSTORE_ALIAS")
            keyPassword = System.getenv("ANDROID_NIGHTLY_KEYSTORE_PASSWORD")
        }

        create("release") {
            val ksPath = keystoreProperties.getProperty("storeFile")
                ?: System.getenv("ANDROID_RELEASE_KEYSTORE")
                ?: sequenceOf(
                    "${System.getProperty("user.home")}/Documents/android-keys/release.jks",
                    "${System.getProperty("user.home")}/android_keys/release.jks"
                ).firstOrNull { file(it).exists() }
                ?: "${System.getProperty("user.home")}/Documents/android-keys/release.jks"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: System.getenv("ANDROID_RELEASE_KEYSTORE_ALIAS")
                    ?: "vmusic"
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")
                    ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            // separate id so debug and release install side by side
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "VMusic"
        }

        release {
            versionNameSuffix = "-RELEASE"
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appName"] = "VMusic"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile?.exists() == true }
                ?: signingConfigs.getByName("debug")        }

    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes.add("META-INF/**/*")
        jniLibs.useLegacyPackaging = false
    }

    // ABI splits: only ship arm64-v8a + armeabi-v7a in the debug APK.
    // x86 / x86_64 are emulator ABIs that inflate the APK by ~3× on real phones.
    // This drops the debug APK from ~97 MB → ~30 MB with zero runtime impact
    // on OnePlus / Redmi / any real ARM device.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("ExtraTranslation", "MissingTranslation")
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

kotlin {
    // jvmToolchain(libs.versions.jvm.get().toInt())

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_5)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)

        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xconsistent-data-class-copy-visibility"
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

composeCompiler {
    if (project.findProperty("enableComposeCompilerReports") == "true") {
        val dest = layout.buildDirectory.dir("compose_metrics")
        metricsDestination = dest
        reportsDestination = dest
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugaring)

    implementation(projects.compose.persist)
    implementation(projects.compose.preferences)
    implementation(projects.compose.routing)
    implementation(projects.compose.reordering)

    implementation(fileTree(projectDir.resolve("vendor")))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.activity)
    implementation(libs.splashscreen)

    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.shimmer)
    implementation(libs.compose.lottie)
    implementation(libs.compose.material3)

    implementation(libs.coil.compose)
    implementation(libs.coil.ktor)

    implementation(libs.compose.cloudy)

    // MLKit translate + language-id removed — saves ~68 MB (libtranslate_jni.so).
    // Lyrics translation feature removed; English-only app.

    implementation(libs.palette)
    implementation(libs.monet)
    runtimeOnly(projects.core.materialCompat)

    implementation(libs.exoplayer)
    implementation(libs.exoplayer.hls)
    implementation(libs.exoplayer.workmanager)
    implementation(libs.media3.session)
    implementation(libs.media)

    implementation(libs.workmanager)
    implementation(libs.workmanager.ktx)

    implementation(libs.credentials)
    implementation(libs.credentials.play)

    implementation(libs.kotlin.coroutines)
    implementation(libs.kotlin.immutable)
    implementation(libs.kotlin.datetime)

    implementation(libs.room)
    ksp(libs.room.compiler)

    // Logging: Timber only — log4j/slf4j/logback removed (saves ~500 KB, no JVM logging stack needed on Android)
    implementation(libs.timber)
    implementation(libs.innertubex)
    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)

    implementation(projects.providers.github)
    implementation(projects.providers.innertube)
    implementation(projects.providers.kugou)
    implementation(projects.providers.lrclib)
    implementation(projects.providers.piped)
    implementation(projects.providers.sponsorblock)
    implementation(projects.providers.translate)
    implementation(projects.core.data)
    implementation(projects.core.ui)

    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.formatting)
}
