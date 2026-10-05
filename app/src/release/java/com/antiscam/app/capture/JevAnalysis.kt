package com.antiscam.app.capture

/** Production credentials and direct TypeSafe access are not part of the release app. */
object JevAnalysis {
    val available = false

    @Suppress("UNUSED_PARAMETER")
    fun setKey(value: String): Boolean = false

    fun clearKey() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun evaluate(text: String, captureId: Int, runId: String, isCurrent: () -> Boolean) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun cancel(reason: String) = Unit
}
