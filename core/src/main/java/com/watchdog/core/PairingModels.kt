package com.watchdog.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CameraPairingSession(
    val cameraDeviceId: String,
    val oneTimePairingCode: String,
    val allowedViewerFingerprints: Set<String> = emptySet()
)

object PairingBootstrap {
    fun createLocalDevicePairing(): CameraPairingSession {
        return CameraPairingSession(
            cameraDeviceId = "cam-${UUID.randomUUID()}",
            oneTimePairingCode = UUID.randomUUID().toString().take(8)
        )
    }
}

object TrustedViewerRegistry {
    // Placeholder in-memory registry. Replace with Room/EncryptedStore persistence.
    private val trustedFingerprints = mutableSetOf("viewer-device-fingerprint-sample")

    fun isTrustedViewer(fingerprint: String): Boolean = fingerprint in trustedFingerprints

    fun trustViewer(fingerprint: String) {
        trustedFingerprints += fingerprint
    }

    fun revokeViewer(fingerprint: String) {
        trustedFingerprints -= fingerprint
    }
}
