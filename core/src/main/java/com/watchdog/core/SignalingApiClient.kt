package com.watchdog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class SignalingApiClient(
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun registerCamera(request: SignalingRegisterRequest): Boolean {
        val response = post("/api/register-camera", json.encodeToString(request))
        return response.first in 200..299
    }

    fun sendOffer(request: SignalingOfferRequest): SignalingOfferAck {
        val response = post("/api/offer", json.encodeToString(request))
        return if (response.first in 200..299) {
            json.decodeFromString(SignalingOfferAck.serializer(), response.second)
        } else {
            SignalingOfferAck(accepted = false, reason = "Signaling request failed: ${response.first}")
        }
    }

    fun pollOffer(cameraDeviceId: String): PendingOffer? {
        val encoded = URLEncoder.encode(cameraDeviceId, Charsets.UTF_8.name())
        val response = get("/api/pull-offer?cameraDeviceId=$encoded")
        return if (response.first == 204 || response.second.isBlank()) {
            null
        } else if (response.first in 200..299) {
            json.decodeFromString(PendingOffer.serializer(), response.second)
        } else {
            null
        }
    }

    fun submitAnswer(request: SignalingAnswerRequest): Boolean {
        val response = post("/api/answer", json.encodeToString(request))
        return response.first in 200..299
    }

    fun pollAnswer(sessionId: String): SignalingAnswerStatus {
        val encoded = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        val response = get("/api/answer?sessionId=$encoded")
        return if (response.first in 200..299) {
            json.decodeFromString(SignalingAnswerStatus.serializer(), response.second)
        } else {
            SignalingAnswerStatus(ready = false, reason = "Answer poll failed: ${response.first}")
        }
    }

    fun pushIceCandidate(payload: IceCandidatePayload): Boolean {
        val response = post("/api/ice", json.encodeToString(payload))
        return response.first in 200..299
    }

    fun pullIceCandidates(sessionId: String, forRole: String): List<IceCandidatePayload> {
        val sid = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        val role = URLEncoder.encode(forRole, Charsets.UTF_8.name())
        val response = get("/api/pull-ice?sessionId=$sid&forRole=$role")
        return if (response.first in 200..299 && response.second.isNotBlank()) {
            json.decodeFromString(IceCandidatesResponse.serializer(), response.second).items
        } else {
            emptyList()
        }
    }

    private fun get(path: String): Pair<Int, String> {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 7000

        val code = connection.responseCode
        val input = if (code in 200..299) connection.inputStream else connection.errorStream
        val bodyText = input?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }.orEmpty()
        return code to bodyText
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 7000

        connection.outputStream.use { stream ->
            stream.write(body.toByteArray())
            stream.flush()
        }

        val code = connection.responseCode
        val input = if (code in 200..299) connection.inputStream else connection.errorStream
        val bodyText = input?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }.orEmpty()

        return code to bodyText
    }
}
