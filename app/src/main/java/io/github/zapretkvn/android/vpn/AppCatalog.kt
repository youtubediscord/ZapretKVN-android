package io.github.zapretkvn.android.vpn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
    val suggestion: String? = null,
)

class AppCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        installedApplications()
            .asSequence()
            .filterNot { it.packageName == appContext.packageName }
            .map { application ->
                InstalledApp(
                    packageName = application.packageName,
                    label = runCatching { application.loadLabel(packageManager).toString() }
                        .getOrDefault(application.packageName)
                        .ifBlank { application.packageName },
                    system = application.flags and (
                        ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    ) != 0,
                    enabled = application.enabled,
                    suggestion = PopularAppSuggestions.labelFor(application.packageName),
                )
            }
            .distinctBy(InstalledApp::packageName)
            .sortedWith(
                compareBy<InstalledApp> { it.label.lowercase() }
                    .thenBy(InstalledApp::packageName),
            )
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(
                    PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                ),
            )
        } else {
            packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        }
}

object PopularAppSuggestions {
    private val labelsByPackage = mapOf(
        "com.instagram.android" to "Instagram",
        "com.instagram.lite" to "Instagram Lite",
        "com.google.android.youtube" to "YouTube",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "org.thunderdog.challegram" to "Telegram X",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.discord" to "Discord",
        "org.thoughtcrime.securesms" to "Signal",
        "com.android.chrome" to "Chrome",
        "org.chromium.chrome" to "Chromium",
        "org.mozilla.firefox" to "Firefox",
        "org.mozilla.firefox_beta" to "Firefox Beta",
        "com.microsoft.emmx" to "Microsoft Edge",
        "com.brave.browser" to "Brave",
        "com.opera.browser" to "Opera",
        "com.opera.mini.native" to "Opera Mini",
        "com.sec.android.app.sbrowser" to "Samsung Internet",
        "com.yandex.browser" to "Яндекс Браузер",
        "com.kiwibrowser.browser" to "Kiwi Browser",
    )

    val packageNames: Set<String> = labelsByPackage.keys

    fun labelFor(packageName: String): String? = labelsByPackage[packageName]
}
