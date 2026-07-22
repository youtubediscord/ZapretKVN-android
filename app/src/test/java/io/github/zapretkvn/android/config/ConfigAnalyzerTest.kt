package io.github.zapretkvn.android.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigAnalyzerTest {
    @Test
    fun `profile DNS requires a usable tagged server selection`() {
        assertTrue(
            ConfigAnalyzer.hasProfileDns(
                """{"dns":{"servers":[{"type":"udp","tag":"profile-dns","server":"192.0.2.53"}],"final":"profile-dns"}}""",
            ),
        )
        assertTrue(
            ConfigAnalyzer.hasProfileDns(
                """{"dns":{"servers":[{"type":"local","tag":"profile-dns"}]}}""",
            ),
        )
        assertFalse(
            ConfigAnalyzer.hasProfileDns(
                """{"dns":{"servers":[{"type":"local","tag":"profile-dns"}],"final":"missing"}}""",
            ),
        )
        assertFalse(ConfigAnalyzer.hasProfileDns("""{"route":{}}"""))
        assertFalse(ConfigAnalyzer.hasProfileDns("not-json"))
    }
}
