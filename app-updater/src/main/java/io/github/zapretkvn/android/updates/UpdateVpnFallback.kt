package io.github.zapretkvn.android.updates

import android.content.Intent
import java.io.File

/**
 * App-owned bridge used only after a retryable updater request has failed.
 * The updater module does not know how the host application implements its VPN.
 */
fun interface UpdateVpnFallback {
    suspend fun connect(): UpdateVpnSession
}

/** Restores the VPN state that existed before the temporary updater route was enabled. */
fun interface UpdateVpnSession {
    suspend fun close()
}

/** Creates the app-owned, FileProvider-backed installer intent for a verified APK. */
fun interface UpdateInstallIntentFactory {
    fun create(file: File): Intent
}
