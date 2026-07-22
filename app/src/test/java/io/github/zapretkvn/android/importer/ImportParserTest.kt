package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.profiles.ManagedProfileEditor
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.profiles.ProtocolOutboundBuilders
import io.github.zapretkvn.android.profiles.TlsSettings
import java.util.Base64
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {
    @Test
    fun `plain and base64 subscriptions support six protocol families`() {
        val vmessPayload = Base64.getEncoder().withoutPadding().encodeToString(
            """{"v":"2","ps":"VMess","add":"vm.example","port":"443","id":"22222222-2222-4222-8222-222222222222","aid":"0","scy":"auto","net":"tcp","tls":"tls","sni":"vm.example"}"""
                .toByteArray(),
        )
        val ssCredentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:ss-secret".toByteArray())
        val links = listOf(
            "# comment is allowed",
            "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls#VLESS",
            "vmess://$vmessPayload",
            "trojan://trojan-secret@trojan.example:443?sni=trojan.example#Trojan",
            "ss://$ssCredentials@ss.example:8388#SS",
            "hy2://hy-secret@hy.example:443?sni=hy.example&obfs=salamander&obfs-password=obfs#HY2",
            "tuic://33333333-3333-4333-8333-333333333333:tuic-secret@tuic.example:443?sni=tuic.example&congestion_control=bbr#TUIC",
        ).joinToString("\n")

        val plain = ImportParser.parse(links, ProfileSource.Clipboard) as ImportCandidate.Managed
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(links.toByteArray())
        val base64 = ImportParser.parse(encoded, ProfileSource.Clipboard) as ImportCandidate.Managed

        val expectedTypes = listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic")
        assertEquals(expectedTypes, plain.servers.map { it.outbound.string("type") })
        assertEquals(expectedTypes, base64.servers.map { it.outbound.string("type") })
        assertEquals(6, ConfigAnalyzer.selectorGroups(plain.buildJson()).single().outbounds.size)
    }

    @Test
    fun `unknown subscription line fails the whole import`() {
        val input = """
            vless://11111111-1111-4111-8111-111111111111@one.example:443
            socks://user:password@unknown.example:1080
        """.trimIndent()

        assertThrows(ImportException::class.java) {
            ImportParser.parse(input, ProfileSource.Clipboard)
        }
    }

    @Test
    fun `ipv6 percent encoding transport tls and reality map to exact json fields`() {
        val vless = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@[2001:db8::1]:443" +
                "?security=reality&sni=edge.example&fp=chrome&pbk=public-key&sid=abcd" +
                "&type=ws&path=%2Fvpn&host=cdn.example#IPv6%20Reality",
            ProfileSource.Qr,
        ) as ImportCandidate.Managed
        val outbound = vless.servers.single().outbound

        assertEquals("2001:db8::1", outbound.string("server"))
        assertEquals("IPv6 Reality", vless.servers.single().displayName)
        assertEquals("ws", (outbound["transport"] as JsonObject).string("type"))
        val tls = outbound["tls"] as JsonObject
        assertEquals("edge.example", tls.string("server_name"))
        assertEquals(
            "public-key",
            (tls["reality"] as JsonObject).string("public_key"),
        )

        val trojan = ImportParser.parse(
            "trojan://p%40ss%3Aword+plus@trojan.example:443#Encoded",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        assertEquals("p@ss:word+plus", trojan.servers.single().outbound.string("password"))
    }

    @Test(timeout = 5_000L)
    fun `parser rejects arbitrary bounded input without non-domain failures`() {
        val random = Random(0x5A17)
        repeat(1_000) {
            val input = buildString {
                repeat(random.nextInt(0, 512)) {
                    append(random.nextInt(0x20, 0x7f).toChar())
                }
            }
            try {
                ImportParser.parse(input, ProfileSource.Clipboard)
            } catch (_: ImportException) {
                // Expected domain result for malformed input.
            }
        }
    }

    @Test
    fun `activity scan reports one set without mutating json`() {
        val raw = """
            {
              "log": {"level": "debug"},
              "ntp": {"enabled": true, "server": "time.example"},
              "outbounds": [{"type": "urltest", "tag": "auto", "outbounds": ["direct"]}],
              "route": {"rule_set": [{"type": "remote", "tag": "remote", "url": "https://rules.example/a.srs"}]},
              "experimental": {"clash_api": {"external_controller": "127.0.0.1:9090"}},
              "heartbeat": "10s"
            }
        """.trimIndent()

        val flags = ImportedConfigActivityScanner.scan(raw)

        assertEquals(ImportedActivityFlag.entries.toSet(), flags)
        assertTrue(ImportedConfigActivityScanner.warning(flags)!!.endsWith("JSON не будет изменён."))
    }

    @Test
    fun `redactor masks uri json uuid and subscription query`() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        val input = """vless://$uuid@vpn.example:443?token=secret https://sub.example/list?token=secret {"password":"secret"}"""

        val output = SecretRedactor.redactInline(input)

        assertFalse(uuid in output)
        assertFalse("token=secret" in output)
        assertFalse("\"password\":\"secret\"" in output)
        assertTrue(SecretRedactor.MASK in output)
    }

    @Test
    fun `managed refresh preserves selector default and unknown fields then falls back`() {
        val one = server("One", "one.example")
        val two = server("Two", "two.example")
        val three = server("Three", "three.example")
        val original = ManagedProfileFactory.subscription(listOf(one, two))
        val secondTag = ManagedProfileFactory.stableTags(listOf(one, two))[1]
        val selected = ConfigAnalyzer.selectServer(original, ConfigAnalyzer.MANAGED_SELECTOR_TAG, secondTag)
        val selectedRoot = JsonConfig.parse(selected) as JsonObject
        val withUnknown = JsonConfig.format(
            JsonObject(selectedRoot.toMutableMap().apply {
                this["extended_unknown"] = JsonPrimitive("kept")
            }),
        )

        val preserved = ManagedProfileEditor.refreshServers(withUnknown, listOf(two, three))
        assertEquals(secondTag, preserved.selectedTag)
        assertFalse(preserved.selectionChanged)
        assertEquals(
            "kept",
            ((JsonConfig.parse(preserved.json) as JsonObject)["extended_unknown"] as JsonPrimitive).content,
        )

        val fallback = ManagedProfileEditor.refreshServers(preserved.json, listOf(three))
        assertTrue(fallback.selectionChanged)
        assertEquals(
            ConfigAnalyzer.selectorGroups(fallback.json).single().outbounds.first(),
            fallback.selectedTag,
        )
    }

    @Test
    fun `single server append changes only managed outbound list`() {
        val original = ManagedProfileFactory.single(server("One", "one.example"))
        val rootBefore = JsonConfig.parse(original) as JsonObject

        val update = ManagedProfileEditor.appendServer(original, server("Two", "two.example"))

        val rootAfter = JsonConfig.parse(update.json) as JsonObject
        assertEquals(rootBefore["inbounds"], rootAfter["inbounds"])
        assertEquals(rootBefore["route"], rootAfter["route"])
        assertEquals(2, ConfigAnalyzer.selectorGroups(update.json).single().outbounds.size)
        val outbounds = rootAfter["outbounds"] as JsonArray
        assertEquals(4, outbounds.size)
    }

    private fun server(name: String, host: String) = ProtocolOutboundBuilders.vless(
        displayName = name,
        server = host,
        serverPort = 443,
        uuid = "11111111-1111-4111-8111-111111111111",
        tls = TlsSettings(enabled = true, serverName = host),
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
