import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.ksp) apply false

    alias(libs.plugins.detekt)
}

val clean by tasks.registering(Delete::class) {
    delete(rootProject.layout.buildDirectory.asFile)
}

allprojects {
    group = "com.dexy.vmusic.app"
    version = "1.0.0"

    apply(plugin = "io.gitlab.arturbosch.detekt")

    dependencies {
        detektPlugins(rootProject.libs.detekt.compose)
        detektPlugins(rootProject.libs.detekt.formatting)
    }

    detekt {
        buildUponDefaultConfig = true
        allRules = false
        ignoreFailures = true
        config.setFrom("$rootDir/detekt.yml")
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "22"
        reports {
            html.required = true
        }
    }
}
