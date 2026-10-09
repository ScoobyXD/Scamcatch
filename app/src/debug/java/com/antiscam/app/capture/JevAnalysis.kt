package com.antiscam.app.capture

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.antiscam.app.capture.AIInstructions.JEV_MODEL
import com.antiscam.app.capture.AIInstructions.JEV_QUESTION_SET_VERSION
import com.antiscam.app.capture.AIInstructions.JEV_THRESHOLD
import com.antiscam.app.capture.AIInstructions.jevQuestions
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Debug-only client. PhoneCredentials manages optional encrypted persistence separately. */
object JevAnalysis {
    val available = true
    var keyRevision = 0L
        private set
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_RESPONSE_BYTES = 64L * 1024
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private var apiKey: String? = null

    private class Pending(
        val captureId: Int,
        val runId: String,
        val text: String,
        val warningGeneration: Long = ScamIntervention.generation,
        val startedAt: Long = SystemClock.elapsedRealtime(),
    )

    private sealed interface Result {
        data class Completed(val answers: Map<String, Map<String, Any>>) : Result
        data class Failed(val reason: String) : Result
    }

    fun setKey(value: String): Boolean {
        checkMainThread()
        val candidate = value.trim()
        if (value.length > 2048 || value.any { it.code !in 32..126 } ||
            candidate.isEmpty() || candidate.any { it.code !in 33..126 }) return false
        apiKey = candidate
        keyRevision++
        CaptureStore.setJevReady(true)
        return true
    }

    fun clearKey() {
        checkMainThread()
        apiKey = null
        keyRevision++
        CaptureStore.setJevReady(false)
    }

    fun evaluate(text: String, captureId: Int, runId: String) {
        checkMainThread()
        val pending = Pending(captureId, runId, text)
        val key = apiKey
        val reason = when {
            key == null -> "No development API key is loaded."
            text.isBlank() -> "OCR returned no text."
            text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES -> "OCR text exceeds the development request limit."
            else -> null
        }
        if (reason != null) {
            publish(pending, "skipped", reason = reason)
            return
        }
        val body = JSONObject().apply {
            put("model", JEV_MODEL)
            put("state", text)
            put("questions", JSONObject(jevQuestions))
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.typesafe.ai/v1/systemone")
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val call = client.newCall(request)
        publish(pending, "pending")
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val reason = if (e is InterruptedIOException) "The Jev request timed out."
                else "The Jev request could not reach the service."
                handler.post { finish(pending, Result.Failed(reason)) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { readResponse(it) }
                } catch (_: Exception) {
                    // Provider bodies and exception messages can contain OCR or credentials.
                    Result.Failed("Jev returned an unreadable or invalid response.")
                }
                handler.post { finish(pending, result) }
            }
        })
    }

    private fun finish(pending: Pending, result: Result) {
        checkMainThread()
        when (result) {
            is Result.Completed -> publish(pending, "completed", answers = result.answers)
            is Result.Failed -> publish(pending, "failed", reason = result.reason)
        }
    }

    private fun readResponse(response: Response): Result {
        if (!response.isSuccessful) {
            val reason = when (response.code) {
                401, 403 -> "TypeSafe rejected the development API key or its access."
                429 -> "TypeSafe rate-limited this request."
                in 300..399 -> "TypeSafe returned a redirect. The request was not forwarded."
                in 500..599 -> "TypeSafe is temporarily unavailable."
                else -> "TypeSafe rejected this request (HTTP ${response.code})."
            }
            return Result.Failed(reason)
        }
        val body = response.body ?: return Result.Failed("Jev returned an empty response.")
        if (body.contentLength() > MAX_RESPONSE_BYTES) return Result.Failed("Jev returned an oversized response.")
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) return Result.Failed("Jev returned an oversized response.")
        val payload = JSONObject(source.readUtf8())
        if (payload.opt("model") != JEV_MODEL) return Result.Failed("Jev returned an unexpected model version.")
        val returned = payload.optJSONObject("answers")
            ?: return Result.Failed("Jev did not return the configured Noul answers.")
        if (returned.length() != jevQuestions.size) return Result.Failed("Jev returned an unexpected number of Noul answers.")
        val answers = linkedMapOf<String, Map<String, Any>>()
        for (id in jevQuestions.keys) {
            val answer = returned.optJSONObject(id)
            val probability = (answer?.opt("noul") as? Number)?.toDouble()
            if (answer?.opt("type") != "noul" || probability == null || !probability.isFinite() || probability !in 0.0..1.0) {
                return Result.Failed("Jev returned a missing or invalid Noul answer.")
            }
            answers[id] = mapOf("type" to "noul", "noul" to probability)
        }
        return Result.Completed(answers)
    }

    private fun publish(
        pending: Pending,
        status: String,
        answers: Map<String, Map<String, Any>>? = null,
        reason: String? = null,
    ) {
        val flagged = answers?.values?.any { (it.getValue("noul") as Double) >= JEV_THRESHOLD }
        if (CaptureStore.state.value.runId == pending.runId) {
            CaptureStore.setJevResult(status, pending.captureId, flagged)
        }
        if (flagged == true) {
            // Reuse the exact captured evidence and approved questions. Never substitute newer OCR.
            val signals = answers.filterValues { (it.getValue("noul") as Double) >= JEV_THRESHOLD }
                .map { (id, answer) ->
                    mapOf("question" to jevQuestions.getValue(id).getValue("instructions"), "probability" to answer.getValue("noul"))
                }
            val evidence = JSONObject().put("flaggedChecks", org.json.JSONArray(signals))
                .put("capturedText", pending.text).toString()
            ScamIntervention.offer(pending.runId, pending.captureId, evidence, pending.warningGeneration)
        }
        val data = linkedMapOf<String, Any?>(
            "runId" to pending.runId,
            "captureId" to pending.captureId,
            "status" to status,
            "model" to JEV_MODEL,
            "questionSetVersion" to JEV_QUESTION_SET_VERSION,
            "threshold" to JEV_THRESHOLD,
            "questions" to jevQuestions,
        )
        if (status != "pending") data["durationMs"] = SystemClock.elapsedRealtime() - pending.startedAt
        if (answers != null) {
            data["answers"] = answers
            data["flagged"] = flagged
        }
        if (reason != null) data["reason"] = reason
        RunMonitor.emit(if (status == "pending") "jev_started" else "jev_$status", data)
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
