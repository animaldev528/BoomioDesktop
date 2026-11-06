plugins {
    java
    alias(libs.plugins.sentry.jvm.gradle)
}

val sentryAuthToken = providers.environmentVariable("SENTRY_AUTH_TOKEN")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotBlank() }

// ⚠️ Never default to the upstream project ("nuviomedia"/"nuvio-desktop"): a Boomio
// build would upload source context and crash reports to NuvioMedia's org. Override
// with -Psentry.org / -Psentry.project or SENTRY_ORG / SENTRY_PROJECT.
val sentryOrg = providers.gradleProperty("sentry.org")
    .orElse(providers.environmentVariable("SENTRY_ORG"))
    .orElse("boomio")
val sentryProject = providers.gradleProperty("sentry.project")
    .orElse(providers.environmentVariable("SENTRY_PROJECT"))
    .orElse("boomio-desktop")

sentry {
    includeSourceContext.set(true)
    autoUploadSourceContext.set(sentryAuthToken != null)
    additionalSourceDirsForSourceContext.set(
        setOf(
            "../composeApp/src/commonMain/kotlin",
            "../composeApp/src/desktopMain/kotlin",
            "../composeApp/src/fullCommonMain/kotlin",
        ),
    )
    includeDependenciesReport.set(false)
    telemetry.set(false)
    org.set(sentryOrg)
    projectName.set(sentryProject)
    sentryAuthToken?.let(authToken::set)
    autoInstallation {
        enabled.set(false)
    }
}
