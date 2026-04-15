package com.watchdog.viewer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebRtcViewerClient(context: Context) {
    private val rootEglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var renderer: SurfaceViewRenderer? = null
    private var localIceListener: ((IceCandidate) -> Unit)? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    suspend fun createOfferSdp(): String = withContext(Dispatchers.IO) {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { localIceListener?.invoke(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.addSink(renderer)
            }
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver?,
                mediaStreams: Array<out org.webrtc.MediaStream>?
            ) {
                receiver?.track()?.let { track ->
                    if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                        (track as? VideoTrack)?.addSink(renderer)
                    }
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    (track as? VideoTrack)?.addSink(renderer)
                }
            }
        }) ?: error("Unable to create PeerConnection")

        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("viewer-audio", audioSource)
        audioTrack?.let { pc.addTrack(it) }
        peerConnection = pc

        val latch = CountDownLatch(1)
        val offerRef = AtomicReference<String>()
        val errorRef = AtomicReference<String>()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) = Unit
                        override fun onSetSuccess() {
                            offerRef.set(desc.description)
                            latch.countDown()
                        }
                        override fun onCreateFailure(p0: String?) = Unit
                        override fun onSetFailure(err: String?) {
                            errorRef.set(err ?: "setLocalDescription failed")
                            latch.countDown()
                        }
                    }, desc)
                } else {
                    errorRef.set("Offer description is null")
                    latch.countDown()
                }
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(err: String?) {
                errorRef.set(err ?: "createOffer failed")
                latch.countDown()
            }
            override fun onSetFailure(err: String?) = Unit
        }, MediaConstraints())

        latch.await(8, TimeUnit.SECONDS)
        errorRef.get()?.let { error(it) }
        offerRef.get() ?: error("Offer generation timed out")
    }

    fun applyRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(p0: String?) = Unit
            override fun onSetFailure(p0: String?) = Unit
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    fun setOnLocalIceCandidate(listener: (IceCandidate) -> Unit) {
        localIceListener = listener
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun attachRenderer(surfaceViewRenderer: SurfaceViewRenderer) {
        renderer = surfaceViewRenderer
        surfaceViewRenderer.init(rootEglBase.eglBaseContext, null)
        surfaceViewRenderer.setMirror(false)
    }

    fun detachRenderer() {
        renderer?.release()
        renderer = null
    }

    fun close() {
        peerConnection?.close()
        peerConnection = null
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        localIceListener = null
    }
}
