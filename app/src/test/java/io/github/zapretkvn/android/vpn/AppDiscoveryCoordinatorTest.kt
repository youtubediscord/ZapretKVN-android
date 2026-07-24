package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDiscoveryCoordinatorTest {
    @Test
    fun completePrimaryInventorySkipsPackageFallback() {
        var fallbackCalls = 0
        val report = coordinator(
            primary = source(OWN, "com.example.telegram"),
            fallback = AppDiscoverySource {
                fallbackCalls += 1
                listOf(app("should.not.run"))
            },
            launcher = source("com.example.telegram"),
        ).discover()

        assertEquals(AppDiscoveryCompleteness.Complete, report.summary.completeness)
        assertEquals(0, fallbackCalls)
        assertEquals(
            AppDiscoverySourceOutcome.NotRequired,
            report.source(AppDiscoverySourceId.InstalledPackages).outcome,
        )
    }

    @Test
    fun launcherGapTriggersPackageFallbackAndPreservesSourcePriority() {
        val primaryTelegram = app("com.example.telegram", label = "Primary")
        val report = coordinator(
            primary = sourceApps(app(OWN), primaryTelegram),
            fallback = source(OWN, "com.example.telegram", "com.example.browser"),
            launcher = source("com.example.telegram", "com.example.browser"),
        ).discover()

        assertEquals(AppDiscoveryCompleteness.Recovered, report.summary.completeness)
        assertEquals(
            "Primary",
            report.applications.single { it.packageName == "com.example.telegram" }.label,
        )
        assertEquals(
            setOf(OWN, "com.example.telegram", "com.example.browser"),
            report.applications.mapTo(linkedSetOf(), DiscoveredApplication::packageName),
        )
    }

    @Test
    fun nearlyEmptyPrimaryTriggersFallbackEvenWhenLauncherIsEmpty() {
        var fallbackCalls = 0
        val report = coordinator(
            primary = source(OWN),
            fallback = AppDiscoverySource {
                fallbackCalls += 1
                listOf(app(OWN), app("com.example.background"))
            },
            launcher = source(),
        ).discover()

        assertEquals(1, fallbackCalls)
        assertEquals(AppDiscoveryCompleteness.Recovered, report.summary.completeness)
        assertTrue(report.applications.any { it.packageName == "com.example.background" })
    }

    @Test
    fun filteredInventoriesStillReturnLauncherAppsAsPartialCatalog() {
        val report = coordinator(
            primary = source(OWN),
            fallback = source(OWN),
            launcher = source("org.telegram.messenger"),
        ).discover()

        assertEquals(AppDiscoveryCompleteness.Partial, report.summary.completeness)
        assertTrue(report.applications.any { it.packageName == "org.telegram.messenger" })
    }

    @Test
    fun deniedPrimaryCanRecoverThroughPackageFallback() {
        val report = coordinator(
            primary = AppDiscoverySource { throw SecurityException("denied") },
            fallback = source(OWN, "org.telegram.messenger"),
            launcher = source("org.telegram.messenger"),
        ).discover()

        assertEquals(AppDiscoveryCompleteness.Recovered, report.summary.completeness)
        assertEquals(
            AppDiscoverySourceOutcome.PermissionDenied,
            report.source(AppDiscoverySourceId.InstalledApplications).outcome,
        )
    }

    @Test
    fun allFailuresProduceTypedFailedReport() {
        val failure = AppDiscoverySource { throw IllegalStateException("user locked") }
        val report = coordinator(
            primary = failure,
            fallback = failure,
            launcher = failure,
            leanback = failure,
        ).discover()

        assertEquals(AppDiscoveryCompleteness.Failed, report.summary.completeness)
        assertTrue(report.applications.isEmpty())
        assertTrue(
            report.summary.sources.all {
                it.outcome == AppDiscoverySourceOutcome.PlatformUnavailable
            },
        )
    }

    @Test
    fun supplementFailureTriggersFallbackAndRecoversInventory() {
        var fallbackCalls = 0
        val report = coordinator(
            primary = source(OWN, "org.telegram.messenger"),
            fallback = AppDiscoverySource {
                fallbackCalls += 1
                listOf(app(OWN), app("org.telegram.messenger"))
            },
            launcher = AppDiscoverySource { throw RuntimeException("OEM bug") },
        ).discover()

        assertEquals(1, fallbackCalls)
        assertEquals(AppDiscoveryCompleteness.Recovered, report.summary.completeness)
        assertEquals(
            AppDiscoverySourceOutcome.UnexpectedFailure,
            report.source(AppDiscoverySourceId.Launcher).outcome,
        )
    }

    private fun coordinator(
        primary: AppDiscoverySource,
        fallback: AppDiscoverySource,
        launcher: AppDiscoverySource,
        leanback: AppDiscoverySource = emptySource(),
    ) = AppDiscoveryCoordinator(
        ownPackageName = OWN,
        primaryInventory = named(AppDiscoverySourceId.InstalledApplications, primary),
        fallbackInventory = named(AppDiscoverySourceId.InstalledPackages, fallback),
        supplements = listOf(
            named(AppDiscoverySourceId.Launcher, launcher),
            named(AppDiscoverySourceId.LeanbackLauncher, leanback),
        ),
    )

    private fun named(
        id: AppDiscoverySourceId,
        source: AppDiscoverySource,
    ) = NamedAppDiscoverySource(id, source)

    private fun source(vararg packageNames: String): AppDiscoverySource =
        AppDiscoverySource { packageNames.map(::app) }

    private fun sourceApps(vararg applications: DiscoveredApplication): AppDiscoverySource =
        AppDiscoverySource { applications.toList() }

    private fun emptySource(): AppDiscoverySource = AppDiscoverySource { emptyList() }

    private fun app(
        packageName: String,
        label: String = packageName,
    ) = DiscoveredApplication(
        packageName = packageName,
        label = label,
        system = false,
        enabled = true,
    )

    private fun AppDiscoveryReport.source(
        id: AppDiscoverySourceId,
    ): AppDiscoverySourceReport = summary.sources.single { it.source == id }

    private companion object {
        const val OWN = "io.github.zapretkvn.android.debug"
    }
}
