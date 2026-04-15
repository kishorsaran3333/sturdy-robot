package com.watchdog.camera

import com.watchdog.core.IceCandidatePayload
import com.watchdog.core.IceCandidatesResponse
import com.watchdog.core.PendingOffer
import com.watchdog.core.SignalingAnswerRequest
import com.watchdog.core.SignalingAnswerStatus
import com.watchdog.core.SignalingOfferAck
import com.watchdog.core.SignalingOfferRequest
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class CameraLocalSignalingServer(
    private val cameraDeviceId: String,
    private val trustedViewersProvider: () -> Set<String>,
    private val pairingCodeProvider: () -> String,
    private val onOffer: (sessionId: String, sdpOffer: String) -> String?,
    private val onRemoteIce: (sessionId: String, payload: IceCandidatePayload) -> Unit
) : NanoHTTPD(8080) {
    private val json = Json { ignoreUnknownKeys = true }
    private val answers = ConcurrentHashMap<String, String>()
    private val iceToViewer = ConcurrentHashMap<String, ConcurrentLinkedQueue<IceCandidatePayload>>()
    private val offerCache = ConcurrentHashMap<String, PendingOffer>()
    private val sessionCreatedAt = ConcurrentHashMap<String, Long>()

    override fun serve(session: IHTTPSession): Response {
        cleanupExpiredSessions()
        return try {
            when {
                session.method == Method.POST && session.uri == "/api/offer" -> handleOffer(session)
                session.method == Method.GET && session.uri == "/api/answer" -> handleAnswerPoll(session)
                session.method == Method.POST && session.uri == "/api/ice" -> handlePushIce(session)
                session.method == Method.GET && session.uri == "/api/pull-ice" -> handlePullIce(session)
                session.method == Method.GET && session.uri == "/api/pull-offer" -> {
                    jsonResponse(404, """{"error":"pull-offer not used in direct-ip mode"}""")
                }
                session.method == Method.POST && session.uri == "/api/answer" -> {
                    handleAnswerSubmit(session)
                }
                session.method == Method.POST && session.uri == "/api/register-camera" -> {
                    jsonResponse(200, """{"ok":true}""")
                }
                session.method == Method.GET && session.uri == "/health" -> jsonResponse(200, """{"ok":true}""")
                else -> jsonResponse(404, """{"error":"not found"}""")
            }
        } catch (e: Exception) {
            jsonResponse(500, """{"error":"${e.message ?: "internal error"}"}""")
        }
    }

    fun pushLocalIceToViewer(sessionId: String, payload: IceCandidatePayload) {
        val queue = iceToViewer.getOrPut(sessionId) { ConcurrentLinkedQueue() }
        queue.add(payload)
    }

    private fun handleOffer(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = json.decodeFromString(SignalingOfferRequest.serializer(), body)
        if (request.cameraDeviceId != cameraDeviceId) {
            return jsonResponse(404, json.encodeToString(SignalingOfferAck(accepted = false, reason = "camera not found")))
        }
        if (request.viewerFingerprint !in trustedViewersProvider()) {
            return jsonResponse(403, json.encodeToString(SignalingOfferAck(accepted = false, reason = "viewer not trusted")))
        }
        if (request.pairingCode != pairingCodeProvider()) {
            return jsonResponse(403, json.encodeToString(SignalingOfferAck(accepted = false, reason = "invalid pairing code")))
        }

        val sessionId = UUID.randomUUID().toString()
        offerCache[sessionId] = PendingOffer(
            sessionId = sessionId,
            cameraDeviceId = request.cameraDeviceId,
            viewerFingerprint = request.viewerFingerprint,
            sdpOffer = request.sdpOffer
        )
        val answer = onOffer(sessionId, request.sdpOffer)
        if (!answer.isNullOrBlank()) {
            answers[sessionId] = answer
        }
        sessionCreatedAt[sessionId] = System.currentTimeMillis()
        return jsonResponse(200, json.encodeToString(SignalingOfferAck(accepted = true, sessionId = sessionId)))
    }

    private fun handleAnswerPoll(session: IHTTPSession): Response {
        val sessionId = session.parameters["sessionId"]?.firstOrNull().orEmpty()
        val answer = answers[sessionId]
        val payload = if (answer.isNullOrBlank()) {
            SignalingAnswerStatus(ready = false)
        } else {
            SignalingAnswerStatus(ready = true, sdpAnswer = answer)
        }
        return jsonResponse(200, json.encodeToString(payload))
    }

    private fun handleAnswerSubmit(session: IHTTPSession): Response {
        // Compatibility route for existing client method. Usually not needed in direct mode.
        val body = readBody(session)
        val payload = json.decodeFromString(SignalingAnswerRequest.serializer(), body)
        answers[payload.sessionId] = payload.sdpAnswer
        return jsonResponse(200, """{"ok":true}""")
    }

    private fun handlePushIce(session: IHTTPSession): Response {
        val body = readBody(session)
        val payload = json.decodeFromString(IceCandidatePayload.serializer(), body)
        if (payload.fromRole == "viewer") {
            onRemoteIce(payload.sessionId, payload)
        } else if (payload.fromRole == "camera") {
            pushLocalIceToViewer(payload.sessionId, payload)
        }
        return jsonResponse(200, """{"ok":true}""")
    }

    private fun handlePullIce(session: IHTTPSession): Response {
        val sessionId = session.parameters["sessionId"]?.firstOrNull().orEmpty()
        val forRole = session.parameters["forRole"]?.firstOrNull().orEmpty()
        if (forRole != "viewer") {
            return jsonResponse(200, json.encodeToString(IceCandidatesResponse()))
        }
        val queue = iceToViewer.getOrPut(sessionId) { ConcurrentLinkedQueue() }
        val out = mutableListOf<IceCandidatePayload>()
        while (true) {
            val next = queue.poll() ?: break
            out += next
        }
        return jsonResponse(200, json.encodeToString(IceCandidatesResponse(items = out)))
    }

    private fun readBody(session: IHTTPSession): String {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun jsonResponse(code: Int, body: String): Response {
        return newFixedLengthResponse(Response.Status.lookup(code), "application/json", body)
    }

    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val maxAgeMs = 10 * 60 * 1000L
        val expired = sessionCreatedAt.entries
            .filter { now - it.value > maxAgeMs }
            .map { it.key }
        expired.forEach { id ->
            sessionCreatedAt.remove(id)
            answers.remove(id)
            offerCache.remove(id)
            iceToViewer.remove(id)
        }
    }
}
