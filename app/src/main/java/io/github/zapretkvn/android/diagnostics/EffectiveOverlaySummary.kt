package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A structural view of the effective runtime overlay. It never copies endpoints or match values. */
object EffectiveOverlaySummary {
    fun create(runtimeJson: String, dnsMode: DnsMode): String {
        val root = JsonConfig.parse(runtimeJson) as? JsonObject ?: error("Runtime JSON is not an object.")
        val dns = root["dns"] as? JsonObject
        val route = root["route"] as? JsonObject
        val tun = (root["inbounds"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.singleOrNull { it.string("type") == "tun" }

        val managedDnsServers = (dns?.get("servers") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .filter { it.string("tag")?.startsWith(MANAGED_PREFIX) == true }
        val managedDnsRules = (dns?.get("rules") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .filter(::isManagedDnsRule)
        val managedRouteRules = (route?.get("rules") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .filter(::isManagedRouteRule)
        val managedRuleSets = (route?.get("rule_set") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .filter { it.string("tag")?.startsWith(MANAGED_PREFIX) == true }
        val bootstrap = managedDnsServers.firstOrNull { it.string("tag") == BOOTSTRAP_TAG }
        val bootstrapAddresses = ((bootstrap?.get("predefined") as? JsonObject)
            ?.values
            ?.firstOrNull() as? JsonArray)
            ?.size ?: 0

        return JsonConfig.format(
            buildJsonObject {
                put("dns_mode", dnsMode.name)
                put(
                    "tun",
                    buildJsonObject {
                        put("present", tun != null)
                        put("auto_route", tun?.boolean("auto_route") == true)
                        put("ipv4", tun.hasAddressFamily(ipv6 = false))
                        put("ipv6", tun.hasAddressFamily(ipv6 = true))
                    },
                )
                put(
                    "dns_servers",
                    buildJsonArray {
                        managedDnsServers.forEach { server ->
                            add(
                                buildJsonObject {
                                    put("tag", safeManagedTag(server.string("tag")))
                                    put("type", safeToken(server.string("type")))
                                    put("uses_proxy_detour", server.string("detour") != null)
                                },
                            )
                        }
                    },
                )
                put("dns_rule_count", managedDnsRules.size)
                put("route_rule_count", managedRouteRules.size)
                put(
                    "route_actions",
                    JsonArray(
                        managedRouteRules.mapNotNull { it.string("action") }
                            .map(::safeToken)
                            .distinct()
                            .map(::JsonPrimitive),
                    ),
                )
                put(
                    "rule_sets",
                    buildJsonArray {
                        managedRuleSets.forEach { ruleSet ->
                            add(
                                buildJsonObject {
                                    put("tag", safeManagedTag(ruleSet.string("tag")))
                                    put("type", safeToken(ruleSet.string("type")))
                                    ruleSet.string("format")?.let { put("format", safeToken(it)) }
                                },
                            )
                        }
                    },
                )
                put("bootstrap_lkg", bootstrap != null)
                put("bootstrap_address_count", bootstrapAddresses)
            },
        )
    }

    private fun isManagedDnsRule(rule: JsonObject): Boolean =
        rule.string("server")?.startsWith(MANAGED_PREFIX) == true ||
            rule.string("action") == "reject" && rule.keys.any(::managedKey)

    private fun isManagedRouteRule(rule: JsonObject): Boolean =
        rule.string("action") == "hijack-dns" ||
            rule.string("outbound")?.startsWith(MANAGED_PREFIX) == true ||
            rule.values.any(::containsManagedTag)

    private fun containsManagedTag(element: JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull?.startsWith(MANAGED_PREFIX) == true
        is JsonArray -> element.any(::containsManagedTag)
        is JsonObject -> element.values.any(::containsManagedTag)
    }

    private fun managedKey(value: String): Boolean = value.startsWith(MANAGED_PREFIX)

    private fun JsonObject?.hasAddressFamily(ipv6: Boolean): Boolean =
        ((this?.get("address") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }).orEmpty()
            .any { address -> (':' in address) == ipv6 }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun safeManagedTag(value: String?): String =
        value?.takeIf { it.startsWith(MANAGED_PREFIX) }?.take(80) ?: "zapret-unknown"

    private fun safeToken(value: String?): String =
        value?.takeIf { TOKEN.matches(it) }?.take(40) ?: "unknown"

    private val TOKEN = Regex("[a-zA-Z0-9_-]+")
    private const val MANAGED_PREFIX = "zapret-"
    private const val BOOTSTRAP_TAG = "zapret-bootstrap-lkg"
}
