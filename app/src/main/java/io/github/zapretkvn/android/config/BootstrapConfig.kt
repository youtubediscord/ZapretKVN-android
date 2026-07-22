package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ProxyBootstrapTarget(
    val outboundTag: String,
    val outboundType: String,
    val hostname: String,
    val port: Int,
    val requiresDns: Boolean,
    val tcpPreflightSupported: Boolean,
    val staleAddressAllowed: Boolean,
)

data class BootstrapHostOverlay(
    val outboundTag: String,
    val hostname: String,
    val addresses: List<String>,
)

object BootstrapConfig {
    fun selectedProxyTag(raw: String): String? = selectedProxyTag(JsonConfig.parse(raw) as? JsonObject ?: return null)

    fun target(raw: String): ProxyBootstrapTarget? {
        val root = JsonConfig.parse(raw) as? JsonObject ?: return null
        val outbounds = (root["outbounds"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val byTag = outbounds.mapNotNull { item -> item.string("tag")?.let { it to item } }.toMap()
        var tag = selectedProxyTag(root) ?: return null
        val visited = mutableSetOf<String>()
        while (visited.add(tag)) {
            val outbound = byTag[tag] ?: return null
            val type = outbound.string("type") ?: return null
            if (type == "selector" || type == "urltest") {
                val members = (outbound["outbounds"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                tag = outbound.string("default")?.takeIf(members::contains) ?: members.firstOrNull() ?: return null
                continue
            }
            val hostname = outbound.string("server")?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val requiresDns = !looksNumeric(hostname)
            val port = (outbound["server_port"] as? JsonPrimitive)?.intOrNull ?: return null
            if (port !in 1..65535) return null
            val tls = outbound["tls"] as? JsonObject
            val authenticatedTls = tls?.let {
                it.boolean("enabled") == true && it.boolean("insecure") != true
            } == true
            return ProxyBootstrapTarget(
                outboundTag = tag,
                outboundType = type,
                hostname = hostname,
                port = port,
                requiresDns = requiresDns,
                tcpPreflightSupported = type !in UDP_TRANSPORTS,
                staleAddressAllowed = requiresDns && authenticatedTls,
            )
        }
        return null
    }

    private fun selectedProxyTag(root: JsonObject): String? {
        val outbounds = (root["outbounds"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        if (outbounds.any { it.string("tag") == ConfigAnalyzer.MANAGED_SELECTOR_TAG }) {
            return ConfigAnalyzer.MANAGED_SELECTOR_TAG
        }
        val final = (root["route"] as? JsonObject)?.string("final") ?: return null
        val finalType = outbounds.firstOrNull { it.string("tag") == final }?.string("type")
        return final.takeUnless { finalType in NON_PROXY_TYPES }
    }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun looksNumeric(value: String): Boolean = ':' in value || IPV4.matches(value)

    private val IPV4 = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
    private val UDP_TRANSPORTS = setOf("hysteria", "hysteria2", "tuic", "wireguard")
    private val NON_PROXY_TYPES = setOf("direct", "block", "dns", null)
}
