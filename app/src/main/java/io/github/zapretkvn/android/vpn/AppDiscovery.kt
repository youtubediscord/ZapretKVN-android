package io.github.zapretkvn.android.vpn

internal data class DiscoveredApplication(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
)

internal enum class AppDiscoverySourceId {
    InstalledApplications,
    InstalledPackages,
    Launcher,
    LeanbackLauncher,
}

internal enum class AppDiscoverySourceOutcome {
    Success,
    PermissionDenied,
    PlatformUnavailable,
    UnexpectedFailure,
    NotRequired,
}

internal data class AppDiscoverySourceReport(
    val source: AppDiscoverySourceId,
    val outcome: AppDiscoverySourceOutcome,
    val itemCount: Int,
)

internal enum class AppDiscoveryCompleteness {
    Complete,
    Recovered,
    Partial,
    Failed,
}

internal data class AppDiscoverySummary(
    val completeness: AppDiscoveryCompleteness,
    val sources: List<AppDiscoverySourceReport>,
)

internal data class AppDiscoveryReport(
    val applications: List<DiscoveredApplication>,
    val summary: AppDiscoverySummary,
)

internal fun interface AppDiscoverySource {
    fun discover(): List<DiscoveredApplication>
}

internal data class NamedAppDiscoverySource(
    val id: AppDiscoverySourceId,
    val source: AppDiscoverySource,
)

internal class AppDiscoveryCoordinator(
    private val ownPackageName: String,
    private val primaryInventory: NamedAppDiscoverySource,
    private val fallbackInventory: NamedAppDiscoverySource,
    private val supplements: List<NamedAppDiscoverySource>,
) {
    fun discover(): AppDiscoveryReport {
        val primary = execute(primaryInventory)
        val supplementResults = supplements.map(::execute)
        val primaryPackages = primary.applications.packageNames()
        val supplementPackages = supplementResults
            .asSequence()
            .flatMap { it.applications.asSequence() }
            .map(DiscoveredApplication::packageName)
            .toSet()
        val supplementFailed = supplementResults.any {
            it.report.outcome != AppDiscoverySourceOutcome.Success
        }
        val fallbackRequired = primary.report.outcome != AppDiscoverySourceOutcome.Success ||
            (primaryPackages - ownPackageName).isEmpty() ||
            supplementFailed ||
            !primaryPackages.containsAll(supplementPackages)
        val fallback = if (fallbackRequired) {
            execute(fallbackInventory)
        } else {
            SourceResult(
                applications = emptyList(),
                report = AppDiscoverySourceReport(
                    source = fallbackInventory.id,
                    outcome = AppDiscoverySourceOutcome.NotRequired,
                    itemCount = 0,
                ),
            )
        }

        val merged = linkedMapOf<String, DiscoveredApplication>()
        sequenceOf(primary, fallback)
            .plus(supplementResults.asSequence())
            .flatMap { it.applications.asSequence() }
            .forEach { application ->
                merged.putIfAbsent(application.packageName, application)
            }

        val inventoryPackages = sequenceOf(primary, fallback)
            .flatMap { it.applications.asSequence() }
            .map(DiscoveredApplication::packageName)
            .toSet()
        val fallbackRecoveredInventory =
            fallback.report.outcome == AppDiscoverySourceOutcome.Success &&
                inventoryPackages.containsAll(supplementPackages)
        val allResults = sequenceOf(primary, fallback).plus(supplementResults.asSequence())
        val completeness = when {
            allResults.none { it.report.outcome == AppDiscoverySourceOutcome.Success } ->
                AppDiscoveryCompleteness.Failed

            !fallbackRequired &&
                primary.report.outcome == AppDiscoverySourceOutcome.Success ->
                AppDiscoveryCompleteness.Complete

            fallbackRequired && fallbackRecoveredInventory ->
                AppDiscoveryCompleteness.Recovered

            else -> AppDiscoveryCompleteness.Partial
        }

        return AppDiscoveryReport(
            applications = merged.values.toList(),
            summary = AppDiscoverySummary(
                completeness = completeness,
                sources = buildList {
                    add(primary.report)
                    add(fallback.report)
                    addAll(supplementResults.map(SourceResult::report))
                },
            ),
        )
    }

    private fun execute(source: NamedAppDiscoverySource): SourceResult {
        return try {
            val applications = source.source.discover()
                .distinctBy(DiscoveredApplication::packageName)
            SourceResult(
                applications = applications,
                report = AppDiscoverySourceReport(
                    source = source.id,
                    outcome = AppDiscoverySourceOutcome.Success,
                    itemCount = applications.size,
                ),
            )
        } catch (_: SecurityException) {
            failed(source.id, AppDiscoverySourceOutcome.PermissionDenied)
        } catch (_: IllegalStateException) {
            failed(source.id, AppDiscoverySourceOutcome.PlatformUnavailable)
        } catch (_: Exception) {
            failed(source.id, AppDiscoverySourceOutcome.UnexpectedFailure)
        }
    }

    private fun failed(
        source: AppDiscoverySourceId,
        outcome: AppDiscoverySourceOutcome,
    ): SourceResult = SourceResult(
        applications = emptyList(),
        report = AppDiscoverySourceReport(source = source, outcome = outcome, itemCount = 0),
    )

    private data class SourceResult(
        val applications: List<DiscoveredApplication>,
        val report: AppDiscoverySourceReport,
    )
}

private fun List<DiscoveredApplication>.packageNames(): Set<String> =
    mapTo(linkedSetOf(), DiscoveredApplication::packageName)
