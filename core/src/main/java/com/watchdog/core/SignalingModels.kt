package com.watchdog.core

import kotlinx.serialization.Serializable

@Serializable
data class SignalingRegisterRequest(
    val cameraDeviceId: String,
    val cameraHost: String
)

@Serializable
data class SignalingOfferRequest(
    val cameraDeviceId: String,
    val viewerFingerprint: String,
    val pairingCode: String,
    val sdpOffer: String
)

@Serializable
data class SignalingOfferAck(
    val accepted: Boolean,
    val sessionId: String? = null,
    val reason: String? = null
)

@Serializable
data class PendingOffer(
    val sessionId: String,
    val cameraDeviceId: String,
    val viewerFingerprint: String,
    val sdpOffer: String
)

@Serializable
data class SignalingAnswerRequest(
    val sessionId: String,
    val sdpAnswer: String
)

@Serializable
data class SignalingAnswerStatus(
    val ready: Boolean,
    val sdpAnswer: String? = null,
    val reason: String? = null
)

@Serializable
data class IceCandidatePayload(
    val sessionId: String,
    val fromRole: String,
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
)

@Serializable
data class IceCandidatesResponse(
    val items: List<IceCandidatePayload> = emptyList()
)
