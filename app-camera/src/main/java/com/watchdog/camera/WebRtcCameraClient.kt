package com.watchdog.camera

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.RtpTransceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebRtcCameraClient(context: Context) {
    private val rootEglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private val appContext = context.applicationContext
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var localIceListener: ((IceCandidate) -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
            )
            .createPeerConnectionFactory()
    }

    fun createAnswerFromOffer(offerSdp: String): String {
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
            override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver?,
                mediaStreams: Array<out org.webrtc.MediaStream>?
            ) = Unit
            override fun onTrack(transceiver: RtpTransceiver?) = Unit
        }) ?: error("Unable to create camera PeerConnection")
        peerConnection = pc
        ensureLocalMediaTracks()
        videoTrack?.let { pc.addTrack(it) }
        audioTrack?.let { pc.addTrack(it) }

        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        val setRemoteLatch = CountDownLatch(1)
        val remoteError = AtomicReference<String>()
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onSetSuccess() { setRemoteLatch.countDown() }
            override fun onCreateFailure(p0: String?) = Unit
            override fun onSetFailure(err: String?) {
                remoteError.set(err ?: "setRemoteDescription failed")
                setRemoteLatch.countDown()
            }
        }, remoteOffer)
        setRemoteLatch.await(5, TimeUnit.SECONDS)
        remoteError.get()?.let { error(it) }

        val answerLatch = CountDownLatch(1)
        val answerRef = AtomicReference<String>()
        val answerError = AtomicReference<String>()
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) {
                    answerError.set("Answer description is null")
                    answerLatch.countDown()
                    return
                }
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        answerRef.set(desc.description)
                        answerLatch.countDown()
                    }
                    override fun onCreateFailure(p0: String?) = Unit
                    override fun onSetFailure(err: String?) {
                        answerError.set(err ?: "setLocalDescription failed")
                        answerLatch.countDown()
                    }
                }, desc)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(err: String?) {
                answerError.set(err ?: "createAnswer failed")
                answerLatch.countDown()
            }
            override fun onSetFailure(p0: String?) = Unit
        }, MediaConstraints())

        answerLatch.await(8, TimeUnit.SECONDS)
        answerError.get()?.let { error(it) }
        return answerRef.get() ?: error("Answer generation timed out")
    }

    fun setOnLocalIceCandidate(listener: (IceCandidate) -> Unit) {
        localIceListener = listener
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    private fun ensureLocalMediaTracks() {
        if (videoTrack != null && audioTrack != null) return
        textureHelper = SurfaceTextureHelper.create("camera-capture-thread", rootEglBase.eglBaseContext)
        videoCapturer = createFrontOrBackCapturer() ?: error("No camera capturer available")
        videoSource = factory.createVideoSource(false)
        videoCapturer?.initialize(textureHelper, appContext, videoSource?.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 24)
        videoTrack = factory.createVideoTrack("camera-video-track", videoSource)

        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("camera-audio-track", audioSource)
    }

    private fun createFrontOrBackCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val back = names.firstOrNull { enumerator.isBackFacing(it) }
        val selected = front ?: back ?: return null
        return enumerator.createCapturer(selected, null)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        videoCapturer?.dispose()
        videoCapturer = null
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        textureHelper?.dispose()
        textureHelper = null
        peerConnection?.close()
        peerConnection = null
    }
}
