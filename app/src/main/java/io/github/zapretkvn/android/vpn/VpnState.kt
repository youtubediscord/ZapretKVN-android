package io.github.zapretkvn.android.vpn

sealed interface VpnConnectionState {
    data object Stopped : VpnConnectionState
    data class Starting(val profileId: String, val message: String) : VpnConnectionState
    data class Connected(
        val profileId: String,
        val profileName: String,
        val connectedAtEpochMillis: Long,
    ) : VpnConnectionState
    data class Stopping(val profileId: String?) : VpnConnectionState
    data class Error(val message: String) : VpnConnectionState
}

data class RuntimeSelectorGroup(
    val tag: String,
    val type: String,
    val selected: String,
    val selectable: Boolean,
    val items: List<RuntimeOutboundItem>,
) {
    val outbounds: List<String>
        get() = items.map(RuntimeOutboundItem::tag)
}

data class RuntimeOutboundItem(
    val tag: String,
    val type: String,
    val endpoint: String?,
    val pingMillis: Int?,
    val pingMeasuredAtEpochSeconds: Long?,
)

data class TrafficSample(
    val uploadBytesPerSecond: Long,
    val downloadBytesPerSecond: Long,
)

data class VpnSessionStats(
    val profileId: String? = null,
    val connectedAtEpochMillis: Long? = null,
    val externalIp: String? = null,
    val pingMillis: Long? = null,
    val uploadTotalBytes: Long = 0,
    val downloadTotalBytes: Long = 0,
    val samples: List<TrafficSample> = emptyList(),
    val statusStreamActive: Boolean = false,
)
