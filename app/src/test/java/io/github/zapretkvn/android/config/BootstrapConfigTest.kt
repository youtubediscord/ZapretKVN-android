package io.github.zapretkvn.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapConfigTest {
    @Test
    fun `selector resolves to authenticated active server without exposing credentials`() {
        val target = BootstrapConfig.target(
            """
                {
                  "outbounds":[
                    {"type":"vless","tag":"a","server":"vpn.example","server_port":443,"uuid":"secret","tls":{"enabled":true,"server_name":"vpn.example"}},
                    {"type":"selector","tag":"zapret-proxy","outbounds":["a"],"default":"a"}
                  ],
                  "route":{"final":"zapret-proxy"}
                }
            """.trimIndent(),
        )!!

        assertEquals("a", target.outboundTag)
        assertEquals("vpn.example", target.hostname)
        assertEquals(443, target.port)
        assertTrue(target.tcpPreflightSupported)
        assertTrue(target.staleAddressAllowed)
        assertFalse(target.toString().contains("secret"))
    }

    @Test
    fun `plaintext server cannot use stale addresses and literal skips DNS but keeps socket preflight`() {
        val plaintext = BootstrapConfig.target(
            """{"outbounds":[{"type":"socks","tag":"p","server":"proxy.example","server_port":1080}],"route":{"final":"p"}}""",
        )!!
        val literal = BootstrapConfig.target(
            """{"outbounds":[{"type":"vless","tag":"p","server":"203.0.113.7","server_port":443}],"route":{"final":"p"}}""",
        )

        assertFalse(plaintext.staleAddressAllowed)
        assertFalse(literal!!.requiresDns)
        assertTrue(literal.tcpPreflightSupported)
    }

    @Test
    fun `native wireguard endpoint is selected from a full tunnel route`() {
        val raw = """
            {
              "endpoints":[{"type":"wireguard","tag":"wg","address":["10.0.0.2/32"],"private_key":"redacted","peers":[{"address":"wg.example","port":51820,"public_key":"redacted","allowed_ips":["0.0.0.0/0"]}]}],
              "outbounds":[{"type":"direct","tag":"direct"}],
              "route":{"rules":[{"ip_cidr":["0.0.0.0/0"],"action":"route","outbound":"wg"}],"final":"direct"}
            }
        """.trimIndent()

        assertEquals("wg", BootstrapConfig.selectedProxyTag(raw))
        val target = BootstrapConfig.target(raw)!!
        assertEquals("wireguard", target.outboundType)
        assertEquals("wg.example", target.hostname)
        assertEquals(51820, target.port)
        assertFalse(target.tcpPreflightSupported)
        assertFalse(target.staleAddressAllowed)
    }
}
