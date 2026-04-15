package com.watchdog.viewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.watchdog.core.DeviceIdentity
import com.watchdog.core.ViewerConnectionStore
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

class ViewerMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connectionStore = ViewerConnectionStore(this)
        val viewerFingerprint = DeviceIdentity.viewerFingerprint(this)

        setContent {
            var cameraDeviceId by remember { mutableStateOf(connectionStore.loadLastCameraDeviceId()) }
            var pairingCode by remember { mutableStateOf("") }
            var tailscaleHost by remember { mutableStateOf(connectionStore.loadLastCameraHost()) }
            var setupStatus by remember { mutableStateOf("Not paired yet.") }
            var connectStatus by remember { mutableStateOf("Viewer not connected") }
            val streamEngine = remember {
                ViewerStreamEngine(context = this)
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "Watchdog Viewer")
                    Text(text = "Viewer Fingerprint:")
                    Text(text = viewerFingerprint)
                    Text(text = "Share this fingerprint with camera phone once.")

                    OutlinedTextField(
                        value = cameraDeviceId,
                        onValueChange = { cameraDeviceId = it },
                        label = { Text("Camera Device ID") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.uppercase() },
                        label = { Text("One-time Pairing Code") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tailscaleHost,
                        onValueChange = { tailscaleHost = it },
                        label = { Text("Camera Tailscale Host or IP") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(onClick = {
                        val valid = cameraDeviceId.isNotBlank() &&
                            pairingCode.length >= 6 &&
                            tailscaleHost.isNotBlank()
                        if (valid) {
                            connectionStore.saveLastCameraHost(tailscaleHost.trim())
                            connectionStore.saveLastCameraDeviceId(cameraDeviceId.trim())
                            setupStatus = "Pairing saved locally. Camera must trust this viewer fingerprint once."
                        } else {
                            setupStatus = "Fill all fields with valid values."
                        }
                    }) {
                        Text("Save Pairing Setup")
                    }

                    Text("Setup status: $setupStatus")
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { renderer ->
                                streamEngine.attachRenderer(renderer)
                            }
                        }
                    )
                    Button(onClick = {
                        if (cameraDeviceId.isBlank() || tailscaleHost.isBlank()) {
                            connectStatus = "Enter camera device ID and Tailscale host first."
                        } else {
                            connectStatus = "Connecting..."
                            lifecycleScope.launch {
                                connectStatus = streamEngine.connect(
                                    cameraDeviceId = cameraDeviceId.trim(),
                                    viewerFingerprint = viewerFingerprint,
                                    pairingCode = pairingCode.trim().uppercase(),
                                    cameraHost = tailscaleHost.trim()
                                )
                            }
                        }
                    }) {
                        Text("Connect Stream")
                    }
                    Button(onClick = {
                        streamEngine.disconnect()
                        connectStatus = "Viewer disconnected"
                    }) {
                        Text("Disconnect Stream")
                    }
                    Text("Connect status: $connectStatus")
                }
                DisposableEffect(Unit) {
                    onDispose {
                        streamEngine.detachRenderer()
                        streamEngine.disconnect()
                    }
                }
            }
        }
    }
}
