package io.github.zapretkvn.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigAnalyzerOutboundTest {
    @Test
    fun `outbound descriptions expose protocol and safe endpoint only`() {
        val descriptions = ConfigAnalyzer.outboundDescriptions(
            """
            {
              "outbounds": [
                {"type":"vless","tag":"Moscow","server":"vpn.example","server_port":443,"uuid":"secret"},
                {"type":"tuic","tag":"IPv6","server":"2001:db8::1","server_port":8443,"password":"secret"},
                {"type":"direct","tag":"direct"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("vless", descriptions.getValue("Moscow").type)
        assertEquals("vpn.example:443", descriptions.getValue("Moscow").endpoint)
        assertEquals("vpn.example", descriptions.getValue("Moscow").serverHost)
        assertEquals("[2001:db8::1]:8443", descriptions.getValue("IPv6").endpoint)
        assertEquals("2001:db8::1", descriptions.getValue("IPv6").serverHost)
        assertNull(descriptions.getValue("direct").endpoint)
        assertNull(descriptions.getValue("direct").serverHost)
    }
}
