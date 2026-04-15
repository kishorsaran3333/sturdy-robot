package com.watchdog.core

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

private const val PREF_NAME = "watchdog_secure_store"
private const val KEY_CAMERA_ID = "camera_id"
private const val KEY_PAIRING_CODE = "pairing_code"
private const val KEY_TRUSTED_VIEWERS = "trusted_viewers"
private const val KEY_LAST_CAMERA_HOST = "last_camera_host"
private const val KEY_LAST_CAMERA_DEVICE_ID = "last_camera_device_id"

data class CameraPairingState(
    val cameraId: String,
    val pairingCode: String,
    val trustedViewerFingerprints: Set<String>
)

object DeviceIdentity {
    fun viewerFingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        return "viewer-$androidId"
    }
}

class CameraPairingStore(private val context: Context) {
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getOrCreateState(): CameraPairingState {
        val cameraId = securePrefs.getString(KEY_CAMERA_ID, null) ?: createCameraId().also {
            securePrefs.edit().putString(KEY_CAMERA_ID, it).apply()
        }
        val pairingCode = securePrefs.getString(KEY_PAIRING_CODE, null) ?: newPairingCode().also {
            securePrefs.edit().putString(KEY_PAIRING_CODE, it).apply()
        }
        val trusted = securePrefs.getStringSet(KEY_TRUSTED_VIEWERS, emptySet()) ?: emptySet()
        return CameraPairingState(cameraId, pairingCode, trusted)
    }

    fun rotatePairingCode(): String {
        val nextCode = newPairingCode()
        securePrefs.edit().putString(KEY_PAIRING_CODE, nextCode).apply()
        return nextCode
    }

    fun trustViewerFingerprint(fingerprint: String) {
        val current = securePrefs.getStringSet(KEY_TRUSTED_VIEWERS, emptySet()).orEmpty()
        securePrefs.edit().putStringSet(KEY_TRUSTED_VIEWERS, current + fingerprint).apply()
    }

    fun revokeViewerFingerprint(fingerprint: String) {
        val current = securePrefs.getStringSet(KEY_TRUSTED_VIEWERS, emptySet()).orEmpty()
        securePrefs.edit().putStringSet(KEY_TRUSTED_VIEWERS, current - fingerprint).apply()
    }

    fun isTrustedViewer(fingerprint: String): Boolean {
        val current = securePrefs.getStringSet(KEY_TRUSTED_VIEWERS, emptySet()).orEmpty()
        return fingerprint in current
    }

    fun tryPair(cameraId: String, pairingCode: String, viewerFingerprint: String): Boolean {
        val state = getOrCreateState()
        val accepted = state.cameraId == cameraId && state.pairingCode == pairingCode
        if (accepted) {
            trustViewerFingerprint(viewerFingerprint)
        }
        return accepted
    }

    private fun createCameraId(): String = "cam-${UUID.randomUUID()}"

    private fun newPairingCode(): String = UUID.randomUUID().toString().take(8).uppercase()
}

class ViewerConnectionStore(private val context: Context) {
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveLastCameraHost(host: String) {
        securePrefs.edit().putString(KEY_LAST_CAMERA_HOST, host).apply()
    }

    fun loadLastCameraHost(): String = securePrefs.getString(KEY_LAST_CAMERA_HOST, "") ?: ""

    fun saveLastCameraDeviceId(deviceId: String) {
        securePrefs.edit().putString(KEY_LAST_CAMERA_DEVICE_ID, deviceId).apply()
    }

    fun loadLastCameraDeviceId(): String = securePrefs.getString(KEY_LAST_CAMERA_DEVICE_ID, "") ?: ""
}
