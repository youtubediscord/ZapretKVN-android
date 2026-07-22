package io.github.zapretkvn.android.vpn

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAppScopePreflightTest {
    @Test
    fun `empty user allowlist never touches builder`() {
        val added = mutableListOf<String>()
        val result = preflight(available = setOf(OWN_PACKAGE)).apply(
            userAllowlist = emptySet(),
            sink = added::add,
        )

        assertEquals(VpnAppScopeResult.EmptyAllowlist, result)
        assertTrue(added.isEmpty())
    }

    @Test
    fun `missing selected package is rejected before builder`() {
        val added = mutableListOf<String>()
        val result = preflight(available = setOf(OWN_PACKAGE)).apply(
            userAllowlist = setOf("com.example.removed"),
            sink = added::add,
        )

        assertEquals(
            VpnAppScopeResult.MissingApplications(listOf("com.example.removed")),
            result,
        )
        assertTrue(added.isEmpty())
    }

    @Test
    fun `builder failure rejects launch instead of using partial list`() {
        val result = preflight(
            available = setOf("com.example.client", OWN_PACKAGE),
        ).apply(
            userAllowlist = setOf("com.example.client"),
            sink = AllowedApplicationSink { packageName ->
                if (packageName == OWN_PACKAGE) throw IOException("package disappeared")
            },
        )

        assertTrue(result is VpnAppScopeResult.BuilderFailure)
        assertEquals(OWN_PACKAGE, (result as VpnAppScopeResult.BuilderFailure).packageName)
    }

    @Test
    fun `ready scope adds stable user list and hidden health package`() {
        val added = mutableListOf<String>()
        val result = preflight(
            available = setOf("com.example.beta", "com.example.alpha", OWN_PACKAGE),
        ).apply(
            userAllowlist = setOf(" com.example.beta ", "com.example.alpha", OWN_PACKAGE),
            sink = added::add,
        )

        val expected = listOf("com.example.alpha", "com.example.beta", OWN_PACKAGE)
        assertEquals(VpnAppScopeResult.Ready(expected), result)
        assertEquals(expected, added)
    }

    @Test
    fun `exclude mode applies disallowed packages and keeps own app in VPN`() {
        val allowed = mutableListOf<String>()
        val disallowed = mutableListOf<String>()
        val result = preflight(setOf("bypass.app", OWN_PACKAGE)).apply(
            selectedPackages = setOf("bypass.app", OWN_PACKAGE),
            mode = AppScopeMode.Exclude,
            allowedSink = AllowedApplicationSink(allowed::add),
            disallowedSink = DisallowedApplicationSink(disallowed::add),
        )

        assertEquals(
            VpnAppScopeResult.Ready(AppScopeMode.Exclude, listOf("bypass.app")),
            result,
        )
        assertTrue(allowed.isEmpty())
        assertEquals(listOf("bypass.app"), disallowed)
    }

    @Test
    fun `empty exclude list is blocked`() {
        val result = preflight(setOf(OWN_PACKAGE)).apply(
            selectedPackages = setOf(OWN_PACKAGE),
            mode = AppScopeMode.Exclude,
            allowedSink = AllowedApplicationSink { },
            disallowedSink = DisallowedApplicationSink { },
        )

        assertEquals(VpnAppScopeResult.EmptyAllowlist, result)
    }

    @Test
    fun `suggestions contain requested apps and never contain TikTok`() {
        val packages = PopularAppSuggestions.packageNames

        assertTrue("com.instagram.android" in packages)
        assertTrue("com.google.android.youtube" in packages)
        assertTrue("org.telegram.messenger" in packages)
        assertTrue("com.whatsapp" in packages)
        assertTrue("com.discord" in packages)
        assertTrue("org.thoughtcrime.securesms" in packages)
        assertTrue("com.android.chrome" in packages)
        assertTrue("org.mozilla.firefox" in packages)
        assertFalse(packages.any { it.contains("tiktok", ignoreCase = true) })
    }

    private fun preflight(available: Set<String>) = VpnAppScopePreflight(
        ownPackageName = OWN_PACKAGE,
        packageAvailability = PackageAvailability(available::contains),
    )

    private companion object {
        const val OWN_PACKAGE = "io.github.zapretkvn.android"
    }
}
