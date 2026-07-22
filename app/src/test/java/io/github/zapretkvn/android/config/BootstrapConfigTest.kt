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
}
