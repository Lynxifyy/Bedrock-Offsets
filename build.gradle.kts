plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
}

/**
 * Kotlin formatting and style checks.
 *
 * `check` runs these, so a formatting mistake fails CI the same way a test
 * failure does. The check covers Kotlin sources; Gradle Kotlin scripts are left
 * alone because their layout is part of the build configuration rather than the
 * app, and reformatting them would churn every future build change.
 */
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }
    tasks.matching { it.name.contains("KotlinScript") }.configureEach {
        enabled = false
    }
}
