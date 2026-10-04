package com.antiscam.app.capture

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** ADB forwards this abstract socket. No network permission, TCP listener, or Logcat text is needed. */
internal object RunBridge {
    private const val SOCKET_NAME = "antiscam_run"
    private const val MAX_COMMAND_CHARS = 4096
    private const val MAX_PENDING_BYTES = 4 * 1024 * 1024
    private const val MAX_EVENT_BYTES = 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var started = false
    private var connection: Connection? = null
    private lateinit var connected: () -> Unit
    private lateinit var command: (String) -> Unit
    private lateinit var disconnected: (String) -> Unit

    fun initialize(onConnect: () -> Unit, onCommand: (String) -> Unit, onDisconnect: (String) -> Unit) {
        synchronized(lock) {
            if (started) return
            started = true
            connected = onConnect
            command = onCommand
            disconnected = onDisconnect
        }
        thread(name = "antiscam-run-listener", isDaemon = true) {
            try {
                LocalServerSocket(SOCKET_NAME).use { server ->
                    while (!Thread.currentThread().isInterrupted) {
                        val socket = server.accept()
                        val uid = try { socket.peerCredentials.uid } catch (_: Exception) { -1 }
                        if (uid != 0 && uid != 2000 && uid != Process.myUid()) {
                            socket.close()
                            continue
                        }
                        val client = synchronized(lock) {
                            if (connection != null) null else Connection(socket).also { connection = it }
                        }
                        if (client == null) socket.close() else client.start()
                    }
                }
            } catch (_: Exception) {
                main.post { disconnected("bridge_unavailable") }
            }
        }
    }

    fun send(line: String) {
        val client = synchronized(lock) { connection } ?: return
        client.enqueue(line)
    }

    private class Connection(private val socket: LocalSocket) {
        private val closed = AtomicBoolean(false)
        private val queuedBytes = AtomicInteger(0)
        private val queue = ArrayBlockingQueue<ByteArray>(64)
        private var reader: Thread? = null
        private var writer: Thread? = null

        fun start() {
            main.post { if (!closed.get()) connected() }
            writer = thread(name = "antiscam-run-writer", isDaemon = true) {
                try {
                    val output = socket.outputStream
                    while (!closed.get()) {
                        val bytes = queue.take()
                        output.write(bytes)
                        output.flush()
                        queuedBytes.addAndGet(-bytes.size)
                    }
                } catch (_: Exception) {
                    close("dashboard_disconnected")
                }
            }
            reader = thread(name = "antiscam-run-reader", isDaemon = true) {
                try {
                    val input = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                    while (!closed.get()) {
                        val line = readCommand(input) ?: break
                        main.post { if (!closed.get()) command(line) }
                    }
                    close("dashboard_disconnected")
                } catch (_: Exception) {
                    close("dashboard_disconnected")
                }
            }
        }

        fun enqueue(line: String) {
            if (closed.get()) return
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_EVENT_BYTES || queuedBytes.addAndGet(bytes.size) > MAX_PENDING_BYTES || !queue.offer(bytes)) {
                close("dashboard_buffer_full")
            }
        }

        private fun readCommand(input: BufferedReader): String? {
            val line = StringBuilder()
            while (line.length <= MAX_COMMAND_CHARS) {
                val character = input.read()
                if (character == -1) return null
                if (character == '\n'.code) return line.toString().trimEnd('\r')
                line.append(character.toChar())
            }
            close("command_too_long")
            return null
        }

        private fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) {
                if (connection === this) {
                    // Keep the slot until main invalidates the run. Earlier callbacks can only
                    // address this closed connection, never a newly connected dashboard.
                    main.post {
                        try {
                            disconnected(reason)
                        } finally {
                            synchronized(lock) {
                                if (connection === this) connection = null
                            }
                        }
                    }
                }
            }
            try { socket.close() } catch (_: Exception) { /* The socket is already unusable. */ }
            queue.clear()
            reader?.interrupt()
            writer?.interrupt()
        }
    }
}
