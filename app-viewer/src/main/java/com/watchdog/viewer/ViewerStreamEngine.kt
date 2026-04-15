package com.watchdog.viewer

import android.content.Context
import com.watchdog.core.IceCandidatePayload
import com.watchdog.core.SignalingApiClient
import com.watchdog.core.SignalingOfferRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SurfaceViewRenderer

class ViewerStreamEngine(
    context: Context
) {
    private val webRtcClient = WebRtcViewerClient(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var icePollJob: Job? = null
    private var signaling: SignalingApiClient? = null

    suspend fun connect(
        cameraDeviceId: String,
        viewerFingerprint: String,
        pairingCode: String,
        cameraHost: String
    ): String {
        return try {
            val signalingBaseUrl = normalizeBaseUrl(cameraHost)
            val signalingClient = SignalingApiClient(signalingBaseUrl)
            signaling = signalingClient
            val sdpOffer = webRtcClient.createOfferSdp()
            val offerAck = signalingClient.sendOffer(
                SignalingOfferRequest(
                    cameraDeviceId = cameraDeviceId,
                    viewerFingerprint = viewerFingerprint,
                    pairingCode = pairingCode,
                    sdpOffer = sdpOffer
                )
            )

            if (!offerAck.accepted) {
                return "Connection denied: ${offerAck.reason ?: "camera rejected"}"
            }

            val sessionId = offerAck.sessionId ?: return "Connection failed: missing session id"
            webRtcClient.setOnLocalIceCandidate { candidate ->
                signalingClient.pushIceCandidate(
                    IceCandidatePayload(
                        sessionId = sessionId,
                        fromRole = "viewer",
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                )
            }
            repeat(20) {
                val status = signalingClient.pollAnswer(sessionId)
                if (status.ready && !status.sdpAnswer.isNullOrBlank()) {
                    webRtcClient.applyRemoteAnswer(status.sdpAnswer)
                    startIcePolling(signalingClient, sessionId)
                    return "Connection accepted via signaling. Waiting for remote video track."
                }
                delay(500)
            }
            "Connection timed out waiting for camera answer"
        } catch (e: Exception) {
            "Connection failed: ${e.message ?: "unknown error"}"
        }
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        webRtcClient.attachRenderer(renderer)
    }

    fun detachRenderer() {
        webRtcClient.detachRenderer()
    }

    private fun startIcePolling(signalingClient: SignalingApiClient, sessionId: String) {
        icePollJob?.cancel()
        icePollJob = scope.launch {
            while (isActive) {
                val candidates = signalingClient.pullIceCandidates(sessionId = sessionId, forRole = "viewer")
                candidates.forEach { item ->
                    webRtcClient.addRemoteIceCandidate(
                        IceCandidate(item.sdpMid, item.sdpMLineIndex, item.candidate)
                    )
                }
                delay(400)
            }
        }
    }

    fun disconnect() {
        icePollJob?.cancel()
        icePollJob = null
        signaling = null
        webRtcClient.close()
    }

    private fun normalizeBaseUrl(cameraHost: String): String {
        val host = cameraHost.trim()
        if (host.startsWith("http://") || host.startsWith("https://")) return host
        return "http://$host:8080"
    }
}
