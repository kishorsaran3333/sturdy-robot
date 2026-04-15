package com.watchdog.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.watchdog.core.CameraPairingStore

class CameraMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pairingStore = CameraPairingStore(this)
        var pendingServiceStart: String? = null

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grantResults ->
            val granted = grantResults[Manifest.permission.CAMERA] == true &&
                grantResults[Manifest.permission.RECORD_AUDIO] == true
            val pending = pendingServiceStart
            if (granted && pending != null) {
                val intent = Intent(this, CameraStreamService::class.java)
                    .putExtra(CameraStreamService.EXTRA_CAMERA_ID, pending)
                startForegroundService(intent)
            }
            pendingServiceStart = null
        }

        setContent {
            var state by remember { mutableStateOf(pairingStore.getOrCreateState()) }
            var fingerprintInput by remember { mutableStateOf("") }
            var cameraHostInput by remember { mutableStateOf("") }
            var streamStatus by remember { mutableStateOf("Camera stream stopped") }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "Watchdog Camera")
                    Text(text = "Pair this once, then viewer can auto-connect.")
                    Text(text = "Device ID: ${state.cameraId}")
                    Text(text = "One-time Pairing Code: ${state.pairingCode}")

                    Button(onClick = {
                        pairingStore.rotatePairingCode()
                        state = pairingStore.getOrCreateState()
                    }) {
                        Text("Rotate Pairing Code")
                    }

                    Text("Trusted Viewer Fingerprints: ${state.trustedViewerFingerprints.size}")
                    if (state.trustedViewerFingerprints.isEmpty()) {
                        Text("No trusted viewers yet.")
                    } else {
                        state.trustedViewerFingerprints.forEach { fingerprint ->
                            Text("- $fingerprint")
                        }
                    }

                    OutlinedTextField(
                        value = fingerprintInput,
                        onValueChange = { fingerprintInput = it },
                        label = { Text("Viewer fingerprint to trust") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (fingerprintInput.isNotBlank()) {
                                    pairingStore.trustViewerFingerprint(fingerprintInput.trim())
                                    state = pairingStore.getOrCreateState()
                                    fingerprintInput = ""
                                }
                            }
                        ) {
                            Text("Trust Viewer")
                        }

                        Button(
                            onClick = {
                                if (fingerprintInput.isNotBlank()) {
                                    pairingStore.revokeViewerFingerprint(fingerprintInput.trim())
                                    state = pairingStore.getOrCreateState()
                                    fingerprintInput = ""
                                }
                            }
                        ) {
                            Text("Revoke Viewer")
                        }
                    }

                    OutlinedTextField(
                        value = cameraHostInput,
                        onValueChange = { cameraHostInput = it },
                        label = { Text("This phone Tailscale host/IP (for viewer entry)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val hasCameraPermission = ContextCompat.checkSelfPermission(
                                    this@CameraMainActivity,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                val hasMicPermission = ContextCompat.checkSelfPermission(
                                    this@CameraMainActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasCameraPermission && hasMicPermission) {
                                    val intent = Intent(this@CameraMainActivity, CameraStreamService::class.java)
                                        .putExtra(CameraStreamService.EXTRA_CAMERA_ID, state.cameraId)
                                    startForegroundService(intent)
                                    streamStatus = "Camera service running on local signaling port 8080"
                                } else {
                                    pendingServiceStart = state.cameraId
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO
                                        )
                                    )
                                    streamStatus = "Requesting camera/mic permissions..."
                                }
                            }
                        ) {
                            Text("Start Camera Service")
                        }

                        Button(
                            onClick = {
                                stopService(Intent(this@CameraMainActivity, CameraStreamService::class.java))
                                streamStatus = "Camera stream stopped"
                            }
                        ) {
                            Text("Stop Service")
                        }
                    }

                    Text(streamStatus)
                    Text("Privacy: keep this phone in visible monitoring mode.")
                }
            }
        }
    }
}
