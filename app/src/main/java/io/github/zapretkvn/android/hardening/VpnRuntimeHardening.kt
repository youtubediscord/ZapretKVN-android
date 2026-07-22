package io.github.zapretkvn.android.hardening

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class TunMtuMode {
    CoreDefault,
    Normalize1500,
}

data class VpnHidingOptions(
    val blockLocalEndpoints: Boolean = true,
    val neutralSessionName: Boolean = false,
    val tunMtuMode: TunMtuMode = TunMtuMode.CoreDefault,
)

sealed interface RuntimeHardeningResult {
    data class Ready(val root: JsonObject) : RuntimeHardeningResult
    data class Blocked(val message: String) : RuntimeHardeningResult
}

/**
 * Applies rootless protections that can be enforced before libbox starts.
 *
 * This module deliberately does not claim to hide Android's VpnService, TUN interface or
 * NetworkCapabilities: those objects are owned by the OS and cannot be spoofed by a regular app.
 */
object VpnRuntimeHardening {
    const val NORMALIZED_MTU = 1500

    fun apply(root: JsonObject, options: VpnHidingOptions): RuntimeHardeningResult {
        if (options.blockLocalEndpoints) {
            extraInbounds(root).takeIf(List<String>::isNotEmpty)?.let { types ->
                return RuntimeHardeningResult.Blocked(
                    "Защита от localhost-чекеров запрещает дополнительные inbounds: " +
                        types.joinToString() + ". Оставьте только Android TUN.",
                )
            }
        }

        var hardened = root
        if (options.blockLocalEndpoints) {
            hardened = removeLocalControlEndpoints(hardened)
        }
        if (options.tunMtuMode == TunMtuMode.Normalize1500) {
            hardened = replaceTunMtu(hardened, NORMALIZED_MTU)
        }
        return RuntimeHardeningResult.Ready(hardened)
    }

    fun sessionName(options: VpnHidingOptions): String =
        if (options.neutralSessionName) "Системная сеть" else "Zapret KVN"

    private fun extraInbounds(root: JsonObject): List<String> =
        (root["inbounds"] as? JsonArray)
            ?.mapIndexedNotNull { index, element ->
                val inbound = element as? JsonObject
                val type = inbound?.string("type")
                if (type == "tun") null else type?.take(40) ?: "#$index без type"
            }
            .orEmpty()

    private fun removeLocalControlEndpoints(root: JsonObject): JsonObject {
        val experimental = (root["experimental"] as? JsonObject)?.toMutableMap()
            ?: return root
        val clash = experimental["clash_api"] as? JsonObject
        if (clash != null) {
            experimental["clash_api"] = JsonObject(
                clash.filterKeys { key -> key !in CLASH_LISTENER_FIELDS },
            )
        }
        // sing-box v2ray_api is a network gRPC control surface. It has no in-process-only mode.
        experimental.remove("v2ray_api")
        return JsonObject(root.toMutableMap().apply {
            this["experimental"] = JsonObject(experimental)
        })
    }

    private fun replaceTunMtu(root: JsonObject, mtu: Int): JsonObject {
        val inbounds = root["inbounds"] as? JsonArray ?: return root
        val runtimeInbounds = JsonArray(inbounds.map { element ->
            val inbound = element as? JsonObject
            if (inbound?.string("type") == "tun") {
                JsonObject(inbound.toMutableMap().apply {
                    this["mtu"] = JsonPrimitive(mtu)
                })
            } else {
                element
            }
        })
        return JsonObject(root.toMutableMap().apply { this["inbounds"] = runtimeInbounds })
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private val CLASH_LISTENER_FIELDS = setOf(
        "external_controller",
        "external_controller_tls",
        "secret",
        "external_ui",
        "external_ui_download_url",
        "external_ui_download_detour",
        "access_control_allow_origin",
        "access_control_allow_private_network",
    )
}
