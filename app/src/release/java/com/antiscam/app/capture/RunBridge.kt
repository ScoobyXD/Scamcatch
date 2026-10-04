package com.antiscam.app.capture

/** Release builds expose no dashboard socket and cannot start a recorded run. */
internal object RunBridge {
    @Suppress("UNUSED_PARAMETER")
    fun initialize(onConnect: () -> Unit, onCommand: (String) -> Unit, onDisconnect: (String) -> Unit) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun send(line: String) = Unit
}
