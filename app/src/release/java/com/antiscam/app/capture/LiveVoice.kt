package com.antiscam.app.capture

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Production credentials and direct OpenAI access are not part of the release app. */
object LiveVoice {
    val available = false
    val ready = false

    @Suppress("UNUSED_PARAMETER")
    fun setKey(value: String): Boolean = false

    fun clearKey() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun start(
        context: Context,
        evidence: String,
        onStatus: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ): VoiceConnection {
        val handler = Handler(Looper.getMainLooper())
        handler.post { onStatus("Voice is available in the development build.") }
        return object : VoiceConnection {
            override fun close(onClosed: () -> Unit) { handler.post(onClosed) }
        }
    }
}
