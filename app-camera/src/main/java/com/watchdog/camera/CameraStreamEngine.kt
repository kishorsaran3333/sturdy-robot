package com.watchdog.camera

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.watchdog.core.CameraPairingStore
import com.watchdog.core.IceCandidatePayload
import org.webrtc.IceCandidate
import java.util.concurrent.atomic.AtomicLong

class CameraStreamEngine(
    private val context: Context
) {
    private var isStreaming: Boolean = false
    private val frameCounter = AtomicLong(0)
    private var webRtcCameraClient: WebRtcCameraClient? = null
    private var localSignalingServer: CameraLocalSignalingServer? = null
    private var cameraId: String = ""

    fun start(
        lifecycleOwner: LifecycleOwner,
        cameraDeviceId: String
    ): Boolean {
        if (isStreaming) return true
        cameraId = cameraDeviceId
        webRtcCameraClient = WebRtcCameraClient(context)

        val pairingStore = CameraPairingStore(context)
        localSignalingServer?.stop()
        localSignalingServer = CameraLocalSignalingServer(
            cameraDeviceId = cameraId,
            trustedViewersProvider = { pairingStore.getOrCreateState().trustedViewerFingerprints },
            pairingCodeProvider = { pairingStore.getOrCreateState().pairingCode },
            onOffer = { sessionId, sdpOffer ->
                webRtcCameraClient?.setOnLocalIceCandidate { candidate ->
                    localSignalingServer?.pushLocalIceToViewer(
                        sessionId = sessionId,
                        payload = IceCandidatePayload(
                            sessionId = sessionId,
                            fromRole = "camera",
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    )
                }
                webRtcCameraClient?.createAnswerFromOffer(sdpOffer)
            },
            onRemoteIce = { _, payload ->
                webRtcCameraClient?.addRemoteIceCandidate(
                    IceCandidate(payload.sdpMid, payload.sdpMLineIndex, payload.candidate)
                )
            }
        ).also { server ->
            server.start()
        }

        isStreaming = true
        return true
    }

    fun stop() {
        localSignalingServer?.stop()
        localSignalingServer = null
        webRtcCameraClient?.close()
        webRtcCameraClient = null
        isStreaming = false
    }

    fun streaming(): Boolean = isStreaming

    fun capturedFrames(): Long = frameCounter.get()
}
