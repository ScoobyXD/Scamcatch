package com.antiscam.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.antiscam.app.capture.AIInstructions.VOICE_EVIDENCE_PREFIX
import com.antiscam.app.capture.AIInstructions.VOICE_GREETING
import com.antiscam.app.capture.AIInstructions.VOICE_MODEL
import com.antiscam.app.capture.AIInstructions.VOICE_NAME
import com.antiscam.app.capture.AIInstructions.VOICE_NO_BACKEND_COMMENTARY
import com.antiscam.app.capture.AIInstructions.VOICE_TRUNCATED_EVIDENCE_SUFFIX
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/** Development BYOK only. PhoneCredentials manages optional encrypted key persistence separately. */
object LiveVoice {
    val available = true
    val ready: Boolean get() = apiKey != null
    var keyRevision = 0L
        private set
    private const val MAX_EVENT_BYTES = 128 * 1024
    private const val START_TIMEOUT_MS = 35_000L
    private const val ICE_TIMEOUT_MS = 10_000L
    private const val CLOSE_TIMEOUT_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        // Retrying session creation can create a second billable session.
        .retryOnConnectionFailure(false)
        .build()
    @Volatile private var apiKey: String? = null
    private var active: Connection? = null
    private var nativeInitialized = false

    fun setKey(value: String): Boolean {
        checkMainThread()
        val candidate = value.trim()
        if (value.length > 2048 || value.any { it.code !in 32..126 } ||
            candidate.isEmpty() || candidate.any { it.code !in 33..126 }) return false
        apiKey = candidate
        keyRevision++
        CaptureStore.setVoiceReady(true)
        return true
    }

    fun clearKey() {
        checkMainThread()
        apiKey = null
        keyRevision++
        CaptureStore.setVoiceReady(false)
        active?.close {}
    }

    fun start(
        context: Context,
        evidence: String,
        onStatus: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ): VoiceConnection {
        checkMainThread()
        val key = apiKey
        val script = RunMonitor.activeVoiceScript()
        val problem = when {
            active != null -> "A voice session is already active."
            key == null -> "Add your OpenAI key on this phone or in the console to enable voice."
            script == null -> "Start a run with a selected voice script before opening voice."
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Microphone permission is required for voice."
            else -> null
        }
        if (problem != null) return unavailable(problem, onStatus)
        val connection = try {
            Connection(context.applicationContext, requireNotNull(script), onStatus, onLevel)
        } catch (_: Exception) {
            return unavailable("Voice could not prepare the phone's audio.", onStatus)
        }
        active = connection
        connection.connect(requireNotNull(key), evidence)
        return connection
    }

    private fun unavailable(status: String, onStatus: (String) -> Unit): VoiceConnection {
        handler.post { onStatus(status) }
        return object : VoiceConnection {
            override fun close(onClosed: () -> Unit) { handler.post(onClosed) }
        }
    }

    private class Connection(
        private val context: Context,
        private val script: VoiceScripts.VoiceScript,
        private val onStatus: (String) -> Unit,
        private val onLevel: (Float) -> Unit,
    ) : VoiceConnection {
        private val audioManager = context.getSystemService(AudioManager::class.java)
        private val stopping = AtomicBoolean(false)
        override val isStopped: Boolean get() = stopping.get()
        private val rtc = Executors.newSingleThreadExecutor { task -> Thread(task, "antiscam-voice-rtc") }
        private val audioLock = Any()
        private val beganAt = SystemClock.elapsedRealtime()
        private val runId = CaptureStore.state.value.runId
        private val closeCallbacks = mutableListOf<() -> Unit>()
        @Volatile private var httpCall: Call? = null
        // Native handles belong to rtc, except ADM's thread-safe mute controls under audioLock.
        private var factory: PeerConnectionFactory? = null
        private var module: JavaAudioDeviceModule? = null
        private var source: AudioSource? = null
        private var microphone: AudioTrack? = null
        private var peer: PeerConnection? = null
        private var events: DataChannel? = null
        private var resourcesReleased = false
        private var localDescriptionSet = false
        private var offerSubmitted = false
        private var pendingSession: JSONObject? = null
        private var pendingKey: String? = null
        // Session/UI state below belongs to the main thread.
        private var started = false
        private var failed = false
        private var transportEnded = false
        private var finalized = false
        private var closed = false
        private var focusHeld = false
        private var routingChanged = false
        private var originalMode = AudioManager.MODE_NORMAL
        private var originalSpeaker = false
        private var originalDevice: AudioDeviceInfo? = null
        private var statsPending = false
        private var firstReceived = false
        private var firstSent = false
        private var firstRemoteEnergy = false
        private var previousEnergy = 0.0
        private var previousDuration = 0.0
        private var previousStatsId: String? = null
        private var latestMetrics: Map<String, Any?> = emptyMap()
        private var usageSeconds: Double? = null
        private val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && !stopping.get()) fail("Voice stopped because another app needs audio.")
            }, handler)
            .build()
        private val startTimeout = Runnable { fail("Voice could not connect. Close this warning to resume scanning.") }
        private val iceTimeout = Runnable { fail("Voice could not prepare a network connection. Try again later.") }
        private val disconnectedTimeout = Runnable { fail("The voice network connection was lost.") }
        private val silenceLevel = Runnable { if (!stopping.get()) onLevel(0f) }
        private val closeTimeout = Runnable {
            if (!transportEnded) {
                if (!failed) onStatus("Voice stopped. Final session usage was not confirmed.")
                endTransport(false)
            }
        }
        private val statsPoll = object : Runnable {
            override fun run() {
                if (stopping.get()) return
                if (!statsPending) {
                    statsPending = true
                    onRtc {
                        if (stopping.get()) return@onRtc
                        peer?.getStats { report ->
                            val arrivedAt = SystemClock.elapsedRealtime()
                            handler.post {
                                statsPending = false
                                if (!stopping.get()) readStats(report, arrivedAt)
                            }
                        }
                    }
                }
                handler.postDelayed(this, 100)
            }
        }

        fun connect(key: String, evidence: String) {
            try {
                onStatus("Preparing voice…")
                prepareAudioRoute()
                handler.postDelayed(startTimeout, START_TIMEOUT_MS)
                val boundedEvidence = if (evidence.length > 6000) {
                    evidence.take(6000) + VOICE_TRUNCATED_EVIDENCE_SUFFIX
                } else evidence
                // The HTTP request starts GPT-Live. Audio format comes from SDP negotiation.
                val session = JSONObject()
                    .put("model", VOICE_MODEL)
                    .put("store", false)
                    .put("instructions", script.instructions)
                    .put("delegation", JSONObject().put("type", "client"))
                    .put("audio", JSONObject().put("output", JSONObject().put("voice", VOICE_NAME)))
                    .put("input", JSONArray().put(JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", JSONArray().put(JSONObject()
                            .put("type", "input_text")
                            .put("text", VOICE_EVIDENCE_PREFIX + boundedEvidence)))))
                onRtc {
                    if (stopping.get()) return@onRtc
                    pendingKey = key
                    pendingSession = session
                    preparePeer()
                }
            } catch (_: SecurityException) {
                fail("Android blocked microphone access. Check microphone permission.")
            } catch (_: Exception) {
                fail("Voice could not prepare the phone's audio.")
            }
        }

        private fun preparePeer() {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission was revoked.")
            }
            if (!nativeInitialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    // Native diagnostics can include SDP, addresses, and identifiers.
                    .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
                    .createInitializationOptions())
                nativeInitialized = true
            }
            if (stopping.get()) return
            val audio = JavaAudioDeviceModule.builder(context)
                .setAudioAttributes(attributes)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(message: String) = audioError()
                    override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, message: String) = audioError()
                    override fun onWebRtcAudioRecordError(message: String) = audioError()
                    private fun audioError() { handler.post { fail("Voice could not use the phone's microphone.") } }
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(message: String) = audioError()
                    override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, message: String) = audioError()
                    override fun onWebRtcAudioTrackError(message: String) = audioError()
                    private fun audioError() { handler.post { fail("Voice could not use the phone's speaker.") } }
                })
                .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() = audioTiming("native_recording_started")
                    override fun onWebRtcAudioRecordStop() = Unit
                })
                .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                    override fun onWebRtcAudioTrackStart() = audioTiming("native_playout_started")
                    override fun onWebRtcAudioTrackStop() = Unit
                })
                .createAudioDeviceModule()
            synchronized(audioLock) {
                module = audio
                audio.setMicrophoneMute(true)
                audio.setSpeakerMute(true)
            }
            if (stopping.get()) return
            val createdFactory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
            factory = createdFactory
            val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val connection = checkNotNull(createdFactory.createPeerConnection(configuration, peerObserver))
            peer = connection
            source = createdFactory.createAudioSource(MediaConstraints())
            val track = createdFactory.createAudioTrack("antiscam-microphone", source)
            microphone = track
            track.setEnabled(false)
            checkNotNull(connection.addTrack(track, listOf("antiscam-voice")))
            // Native WebRTC renders remote audio through ADM; the data channel carries JSON only.
            events = connection.createDataChannel("oai-events", DataChannel.Init()).also {
                it.registerObserver(eventObserver)
            }
            timing("native_peer_ready")
            if (stopping.get()) return
            connection.createOffer(object : DescriptionObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    onRtc {
                        if (stopping.get()) return@onRtc
                        peer?.setLocalDescription(object : DescriptionObserver() {
                            override fun onSetSuccess() {
                                onRtc {
                                    if (stopping.get()) return@onRtc
                                    localDescriptionSet = true
                                    timing("local_offer_set")
                                    handler.post {
                                        if (!stopping.get()) handler.postDelayed(iceTimeout, ICE_TIMEOUT_MS)
                                    }
                                    submitOfferWhenReady()
                                }
                            }
                        }, description)
                    }
                }
            }, MediaConstraints())
        }

        private val peerObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onDataChannel(channel: DataChannel) {
                // This protocol uses only the channel we created before the offer.
                onRtc { runCatching { channel.close() }; runCatching { channel.dispose() } }
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                audioTiming("remote_track_negotiated")
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) onRtc { submitOfferWhenReady() }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                val arrivedAt = SystemClock.elapsedRealtime()
                handler.post {
                    if (stopping.get()) return@post
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            handler.removeCallbacks(disconnectedTimeout)
                            timing("transport_connected", arrivedAt)
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            handler.removeCallbacks(disconnectedTimeout)
                            handler.postDelayed(disconnectedTimeout, 5_000)
                        }
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> fail("The voice network connection ended.")
                        else -> Unit
                    }
                }
            }
        }

        private val eventObserver = object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                onRtc {
                    val state = events?.state()
                    val arrivedAt = SystemClock.elapsedRealtime()
                    handler.post {
                        when (state) {
                            DataChannel.State.OPEN -> if (!stopping.get()) timing("event_channel_open", arrivedAt)
                            DataChannel.State.CLOSED -> {
                                if (!stopping.get()) fail("The voice event connection ended.")
                                endTransport(false)
                            }
                            else -> Unit
                        }
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val arrivedAt = SystemClock.elapsedRealtime()
                if (buffer.binary || buffer.data.remaining() > MAX_EVENT_BYTES) {
                    handler.post { fail("Voice returned an unsupported response.") }
                    return
                }
                // DataChannel releases its buffer after this callback. Copy bounded JSON now.
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val event = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) {
                    handler.post { fail("Voice returned an unreadable response.") }
                    return
                } finally {
                    bytes.fill(0)
                }
                handler.post { receive(event, arrivedAt) }
            }
        }

        private fun submitOfferWhenReady() {
            if (stopping.get() || offerSubmitted || !localDescriptionSet ||
                peer?.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) return
            val sdp = checkNotNull(peer?.localDescription?.description).also { check(it.isNotBlank()) }
            val key = checkNotNull(pendingKey)
            val session = checkNotNull(pendingSession)
            offerSubmitted = true
            pendingKey = null
            pendingSession = null
            timing("ice_gathering_complete")
            handler.post {
                handler.removeCallbacks(iceTimeout)
                if (!stopping.get()) onStatus("OpenAI is starting voice…")
            }
            val body = JSONObject().put("session", session)
                .put("transport", JSONObject().put("type", "webrtc").put("sdp", sdp))
                .toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("https://api.openai.com/v1/live/sessions")
                .header("Authorization", "Bearer $key").post(body).build()
            val call = client.newCall(request)
            httpCall = call
            if (stopping.get()) {
                call.cancel()
                return
            }
            timing("session_request_sent")
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    handler.post { fail("Voice could not connect to OpenAI. Check the network and try again.") }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (stopping.get()) return
                        val arrivedAt = SystemClock.elapsedRealtime()
                        val status = response.code
                        val result = try {
                            val text = response.peekBody(MAX_EVENT_BYTES + 1L).string()
                            if (text.length > MAX_EVENT_BYTES) null else JSONObject(text)
                        } catch (_: Exception) { null }
                        if (!response.isSuccessful) {
                            handler.post {
                                if (result?.optJSONObject("error") != null) providerError(result, status)
                                else httpError(status)
                            }
                            return
                        }
                        val id = result?.optJSONObject("session")?.opt("id") as? String
                        val answer = result?.optJSONObject("transport")?.opt("sdp") as? String
                        if (id.isNullOrBlank() || answer.isNullOrBlank()) {
                            handler.post { fail("OpenAI returned an unreadable voice connection.") }
                            return
                        }
                        onRtc {
                            if (stopping.get()) return@onRtc
                            timing("session_answer_received", arrivedAt)
                            peer?.setRemoteDescription(object : DescriptionObserver() {
                                override fun onSetSuccess() = audioTiming("remote_answer_set")
                            }, SessionDescription(SessionDescription.Type.ANSWER, answer))
                        }
                    }
                }
            })
        }

        private open inner class DescriptionObserver : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) = negotiationError()
            override fun onSetFailure(error: String) = negotiationError()
            private fun negotiationError() {
                handler.post { fail("Voice could not negotiate an audio connection.") }
            }
        }

        private fun receive(event: JSONObject, arrivedAt: Long) {
            if (closed || transportEnded) return
            when (event.optString("type")) {
                "session.closed" -> {
                    usageSeconds = (event.optJSONObject("usage")?.opt("seconds") as? Number)
                        ?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
                    if (!stopping.get()) onStatus("Voice ended. Close this warning to resume scanning.")
                    stopAudio()
                    endTransport(true)
                    return
                }
                "session.usage.updated" -> {
                    if (!stopping.get()) usageSeconds = (event.optJSONObject("usage")?.opt("seconds") as? Number)
                        ?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
                }
            }
            if (stopping.get()) return
            when (event.optString("type")) {
                "session.started" -> {
                    if (started) return
                    started = true
                    handler.removeCallbacks(startTimeout)
                    timing("session_started", arrivedAt)
                    onRtc {
                        if (stopping.get()) return@onRtc
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            throw SecurityException("Microphone permission was revoked.")
                        }
                        synchronized(audioLock) {
                            if (stopping.get()) return@onRtc
                            module?.setMicrophoneMute(false)
                            module?.setSpeakerMute(false)
                            microphone?.setEnabled(true)
                        }
                        sendOnRtc(JSONObject().put("type", "session.instructions.append")
                            .put("event_id", "antiscam_greeting")
                            .put("delegation_id", JSONObject.NULL).put("content", VOICE_GREETING))
                        handler.post {
                            if (!stopping.get()) {
                                onStatus("Voice connected. You can speak.")
                                handler.post(statsPoll)
                            }
                        }
                    }
                }
                "session.instructions.appended" -> {
                    if (event.optString("client_event_id") == "antiscam_greeting") timing("greeting_accepted", arrivedAt)
                }
                "session.delegation.created" -> {
                    val id = (event.optJSONObject("delegation")?.opt("id") as? String)?.takeIf { it.isNotBlank() }
                    if (id != null) onRtc {
                        sendOnRtc(JSONObject().put("type", "session.commentary.append")
                            .put("delegation_id", id)
                            .put("content", VOICE_NO_BACKEND_COMMENTARY))
                    }
                }
                "error" -> providerError(event)
            }
        }

        private fun sendOnRtc(event: JSONObject) {
            if (stopping.get()) return
            if (!sendData(event)) handler.post { fail("The voice event connection stopped.") }
        }

        private fun sendData(event: JSONObject): Boolean {
            val channel = events ?: return false
            return channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() < MAX_EVENT_BYTES &&
                channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray(Charsets.UTF_8)), false))
        }

        private fun readStats(report: RTCStatsReport, arrivedAt: Long) {
            val inbound = report.statsMap.values.firstOrNull {
                it.type == "inbound-rtp" && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio")
            }
            val outbound = report.statsMap.values.firstOrNull {
                it.type == "outbound-rtp" && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio")
            }
            fun number(values: Map<String, Any>?, name: String): Double? =
                (values?.get(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }
            val input = inbound?.members
            val output = outbound?.members
            val received = number(input, "bytesReceived")
            val sent = number(output, "bytesSent")
            latestMetrics = mapOf(
                "rtpBytesReceived" to received, "rtpBytesSent" to sent,
                "rtpPacketsReceived" to number(input, "packetsReceived"),
                "rtpPacketsLost" to number(input, "packetsLost"),
                "rtpJitterSeconds" to number(input, "jitter"),
                "jitterBufferDelaySeconds" to number(input, "jitterBufferDelay"),
                "jitterBufferEmittedCount" to number(input, "jitterBufferEmittedCount"),
                "concealedSamples" to number(input, "concealedSamples"),
                "totalSamplesReceived" to number(input, "totalSamplesReceived"),
                "remoteAudioEnergy" to number(input, "totalAudioEnergy"),
                "remoteAudioDurationSeconds" to number(input, "totalSamplesDuration"),
            )
            if (!firstReceived && received != null && received > 0) {
                firstReceived = true
                timing("first_remote_rtp_observed", arrivedAt)
            }
            if (!firstSent && sent != null && sent > 0) {
                firstSent = true
                timing("first_local_rtp_observed", arrivedAt)
            }
            val energy = number(input, "totalAudioEnergy")
            val duration = number(input, "totalSamplesDuration")
            val sameStream = inbound != null && previousStatsId == inbound.id
            val energyDelta = if (sameStream && energy != null) energy - previousEnergy else 0.0
            val durationDelta = if (sameStream && duration != null) duration - previousDuration else 0.0
            // This is remote receiver energy, not proof that sound reached the user's ears.
            val rawLevel = if (sameStream && energy != null && duration != null) {
                if (durationDelta > 0 && energyDelta >= 0) sqrt(energyDelta / durationDelta) else 0.0
            } else number(input, "audioLevel") ?: 0.0
            previousStatsId = inbound?.id
            previousEnergy = energy ?: 0.0
            previousDuration = duration ?: 0.0
            if (!firstRemoteEnergy && rawLevel > 0) {
                firstRemoteEnergy = true
                timing("first_remote_audio_energy_observed", arrivedAt)
            }
            val level = rawLevel.takeIf { it.isFinite() } ?: 0.0
            onLevel((level * 4).toFloat().coerceIn(0f, 1f))
            handler.removeCallbacks(silenceLevel)
            handler.postDelayed(silenceLevel, 250)
        }

        private fun providerError(event: JSONObject, httpStatus: Int? = null) {
            val error = event.optJSONObject("error")
            // Provider messages can quote request data. Keep only bounded diagnostic identifiers.
            fun identifier(name: String): String? = (error?.opt(name) as? String)?.takeIf {
                it.length in 1..96 && it.matches(Regex("[a-z][a-z0-9_]*(?:[.\\[][a-z0-9_]+\\]?)*"))
            }
            val code = identifier("code")
            val type = identifier("type")
            val param = identifier("param")
            val clientEvent = (error?.opt("client_event_id") as? String)?.takeIf { it.isNotBlank() }
                ?: (event.opt("client_event_id") as? String)
            val command = if (httpStatus != null) "live.sessions.create"
                else if (clientEvent == "antiscam_greeting") "session.instructions.append" else null
            val explanation = when {
                code in setOf("invalid_api_key", "invalid_authentication", "authentication_error") || type == "authentication_error" || httpStatus == 401 ->
                    "OpenAI rejected authentication. Check the OpenAI API key on this phone or in the console."
                code in setOf("insufficient_quota", "billing_hard_limit_reached", "billing_not_active") || type == "insufficient_quota" ->
                    "OpenAI voice has no available API quota. Check the API project's billing and credits."
                code in setOf("forbidden", "model_not_found", "model_not_allowed", "permission_denied", "access_denied") || type == "permission_error" || httpStatus == 403 ->
                    "OpenAI denied voice access. Check the API key's permissions and project access to $VOICE_MODEL."
                code == "rate_limit_exceeded" || type == "rate_limit_error" || httpStatus == 429 ->
                    "OpenAI voice reached a rate limit. Try again later."
                type == "invalid_request_error" ->
                    "OpenAI rejected the app's voice request. The console has the error details."
                type == "server_error" -> "OpenAI reported a server error. Try again later."
                else -> "OpenAI could not continue voice. The console has the error details."
            }
            val identifiers = listOfNotNull(code ?: type, param, command).joinToString("; ")
            fail(explanation + if (identifiers.isEmpty()) "" else " ($identifiers)", mapOf(
                "code" to code, "errorType" to type, "param" to param, "command" to command, "httpStatus" to httpStatus,
            ))
        }

        private fun httpError(status: Int) {
            val explanation = when (status) {
                401 -> "OpenAI rejected authentication. Check the OpenAI API key on this phone or in the console."
                403 -> "OpenAI denied voice access. Check the API key's permissions and project access to $VOICE_MODEL."
                429 -> "OpenAI voice reached a usage limit. Check API billing and rate limits."
                else -> "OpenAI could not start the voice connection."
            }
            fail("$explanation (HTTP $status)", mapOf("httpStatus" to status, "command" to "live.sessions.create"))
        }

        private fun audioTiming(phase: String) {
            val arrivedAt = SystemClock.elapsedRealtime()
            handler.post { if (!stopping.get()) timing(phase, arrivedAt) }
        }

        private fun timing(phase: String, atMillis: Long = SystemClock.elapsedRealtime()) {
            record("voice_timing", mapOf("phase" to phase, "durationMs" to atMillis - beganAt))
        }

        private fun record(type: String, details: Map<String, Any?>) {
            RunMonitor.emit(type, details + mapOf("runId" to runId, "model" to VOICE_MODEL, "voice" to VOICE_NAME,
                "scriptId" to script.id, "scriptRevision" to script.revision, "transport" to "webrtc"))
        }

        private fun onRtc(block: () -> Unit) {
            try {
                rtc.execute {
                    if (resourcesReleased) return@execute
                    try {
                        block()
                    } catch (_: SecurityException) {
                        handler.post { fail("Android blocked microphone access. Check microphone permission.") }
                    } catch (_: LinkageError) {
                        handler.post { fail("This phone could not load the voice audio engine.") }
                    } catch (_: Exception) {
                        handler.post { fail("Voice could not continue its audio connection.") }
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Native callbacks can arrive after deterministic cleanup.
            }
        }

        private fun fail(status: String, details: Map<String, Any?> = emptyMap()) {
            if (closed || stopping.get()) return
            failed = true
            onStatus(status)
            record("voice_failed", latestMetrics + details + mapOf(
                "reason" to status, "phase" to if (started) "conversation" else "startup",
            ))
            close {}
        }

        override fun close(onClosed: () -> Unit) {
            checkMainThread()
            if (closed) {
                onClosed()
                return
            }
            closeCallbacks += onClosed
            if (stopping.get()) return
            stopAudio()
            handler.postDelayed(closeTimeout, CLOSE_TIMEOUT_MS)
            val canCloseSession = started
            onRtc {
                val sent = canCloseSession && runCatching {
                    sendData(JSONObject().put("type", "session.close"))
                }.getOrDefault(false)
                if (!sent) handler.post { endTransport(false) }
            }
        }

        private fun stopAudio() {
            if (!stopping.compareAndSet(false, true)) return
            handler.removeCallbacks(startTimeout)
            handler.removeCallbacks(iceTimeout)
            handler.removeCallbacks(disconnectedTimeout)
            handler.removeCallbacks(statsPoll)
            handler.removeCallbacks(silenceLevel)
            // These ADM controls are thread-safe and silence both directions before close returns.
            synchronized(audioLock) {
                runCatching { module?.setMicrophoneMute(true) }
                runCatching { module?.setSpeakerMute(true) }
            }
            onLevel(0f)
            httpCall?.cancel()
            onRtc {
                pendingKey = null
                pendingSession = null
                runCatching { microphone?.setEnabled(false) }
                runCatching { peer?.setAudioRecording(false) }
                runCatching { peer?.setAudioPlayout(false) }
                runCatching { module?.requestStopRecording() }
            }
        }

        private fun endTransport(graceful: Boolean) {
            if (transportEnded) return
            transportEnded = true
            finalized = graceful
            handler.removeCallbacks(closeTimeout)
            stopAudio()
            onRtc {
                // Never dispose native handles on their own signaling or audio callback threads.
                runCatching { events?.unregisterObserver() }
                runCatching { events?.close() }
                runCatching { events?.dispose() }
                events = null
                runCatching { peer?.close() }
                runCatching { peer?.dispose() }
                peer = null
                runCatching { microphone?.dispose() }
                microphone = null
                runCatching { source?.dispose() }
                source = null
                runCatching { factory?.dispose() }
                factory = null
                synchronized(audioLock) {
                    runCatching { module?.release() }
                    module = null
                }
                pendingKey = null
                pendingSession = null
                httpCall = null
                resourcesReleased = true
                rtc.shutdown()
                handler.post {
                    restoreAudio()
                    closed = true
                    record("voice_audio_summary", latestMetrics + mapOf(
                        "finalizationConfirmed" to finalized, "usageSeconds" to usageSeconds,
                    ))
                    if (active === this) active = null
                    val callbacks = closeCallbacks.toList()
                    closeCallbacks.clear()
                    callbacks.forEach { it() }
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun prepareAudioRoute() {
            focusHeld = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            check(focusHeld)
            originalMode = audioManager.mode
            originalSpeaker = audioManager.isSpeakerphoneOn
            if (Build.VERSION.SDK_INT >= 31) originalDevice = audioManager.communicationDevice
            routingChanged = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                check(speaker != null && audioManager.setCommunicationDevice(speaker))
            } else {
                audioManager.isSpeakerphoneOn = true
            }
        }

        @Suppress("DEPRECATION")
        private fun restoreAudio() {
            if (routingChanged) runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    val previous = originalDevice
                    if (previous == null || !audioManager.setCommunicationDevice(previous)) audioManager.clearCommunicationDevice()
                } else {
                    audioManager.isSpeakerphoneOn = originalSpeaker
                }
                audioManager.mode = originalMode
            }
            if (focusHeld) runCatching { audioManager.abandonAudioFocusRequest(focus) }
        }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

}
