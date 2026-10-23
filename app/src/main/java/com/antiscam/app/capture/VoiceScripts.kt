package com.antiscam.app.capture

import android.content.Context
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The phone keeps the script library and its manual selection together. */
object VoiceScripts {
    data class VoiceScript(val id: String, val name: String, val instructions: String, val revision: String)

    data class State(
        val ready: Boolean = false,
        val working: Boolean = false,
        val error: Boolean = false,
        val message: String? = null,
        val selectedId: String? = null,
        val scripts: List<VoiceScript> = emptyList(),
    )

    private const val MAX_MARKDOWN_BYTES = 2 * 1024 * 1024
    private val activeLine = Regex("ACTIVE_VOICE_SCRIPT = ([a-z][a-z0-9_-]{0,47})")
    private val heading = Regex("## ([a-z][a-z0-9_-]{0,47})(?: \\| (.+))?", RegexOption.DOT_MATCHES_ALL)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var appContext: Context? = null
    private var markdown: String? = null

    fun initialize(context: Context) {
        checkMainThread()
        if (appContext != null) return
        appContext = context.applicationContext
        mutableState.value = State(working = true, message = "Loading voice scripts…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { load() } }
            result.fold(
                onSuccess = { (loadedMarkdown, loadedState) ->
                    markdown = loadedMarkdown
                    mutableState.value = loadedState.copy(message = "Voice scripts loaded.")
                },
                onFailure = { fail(it.messageFor("Voice scripts could not be loaded.")) },
            )
        }
    }

    /** Null means the import is queued. State reports its final result. */
    fun importMarkdown(value: String): String? {
        checkMainThread()
        if (appContext == null) return fail("Reopen ScamCatch before importing voice scripts.")
        if (mutableState.value.working) return fail("Wait for the current voice script operation.", keepWorking = true)
        if (CaptureStore.state.value.runId != null || CaptureStore.state.value.standaloneRunRequested)
            return fail("Stop the current run before changing voice scripts.")
        mutableState.value = mutableState.value.copy(working = true, error = false, message = "Saving voice scripts…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val imported = parse(value)
                    val normalized = normalize(value)
                    write(normalized, checkNotNull(imported.selectedId))
                    normalized to imported
                }
            }
            result.fold(
                onSuccess = { (savedMarkdown, savedState) ->
                    markdown = savedMarkdown
                    mutableState.value = savedState.copy(message = "Voice scripts saved on this phone.")
                },
                onFailure = { fail(it.messageFor("Voice scripts could not be saved. The previous library remains active.")) },
            )
        }
        return null
    }

    /** Selection changes only after the phone saves it. */
    fun select(id: String): String? {
        checkMainThread()
        val current = mutableState.value
        if (current.working) return fail("Wait for the current voice script operation.", keepWorking = true)
        if (CaptureStore.state.value.runId != null || CaptureStore.state.value.standaloneRunRequested)
            return fail("Stop the current run before changing voice scripts.")
        if (!current.ready || markdown == null) return fail("Load voice scripts before selecting a script.")
        if (current.scripts.none { it.id == id }) return fail("Select a script from this phone's library.")
        val savedMarkdown = checkNotNull(markdown)
        mutableState.value = current.copy(working = true, error = false, message = "Saving voice script selection…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { write(savedMarkdown, id) } }
            result.fold(
                onSuccess = {
                    mutableState.value = current.copy(selectedId = id, error = false, message = "Voice script selected on this phone.")
                },
                onFailure = { fail("The voice script selection could not be saved. The previous selection remains active.") },
            )
        }
        return null
    }

    fun selected(): VoiceScript? {
        val current = mutableState.value
        return if (current.ready && !current.working) current.scripts.firstOrNull { it.id == current.selectedId } else null
    }

    fun snapshot(): JSONObject {
        val current = mutableState.value
        return JSONObject().apply {
            put("available", true)
            put("ready", current.ready)
            put("working", current.working)
            put("error", current.error)
            put("message", current.message ?: JSONObject.NULL)
            put("selectedId", current.selectedId ?: JSONObject.NULL)
            put("scripts", JSONArray().apply {
                current.scripts.forEach { script ->
                    put(JSONObject().apply {
                        put("id", script.id)
                        put("name", script.name)
                        put("revision", script.revision)
                    })
                }
            })
        }
    }

    private fun load(): Pair<String, State> {
        val source = file()
        if (!source.exists()) {
            val value = checkNotNull(appContext).assets.open("voice-scripts.md").use { decodeUtf8(it.readBytes()) }
            return normalize(value) to parse(value)
        }
        require(source.length() <= MAX_MARKDOWN_BYTES * 6L + 4096L) { "The saved voice script library is too large." }
        val saved = JSONObject(decodeUtf8(source.readBytes()))
        val value = saved.opt("markdown") as? String
            ?: throw IllegalArgumentException("The saved voice script library has no Markdown source.")
        val selectedId = saved.opt("selectedId") as? String
            ?: throw IllegalArgumentException("The saved voice script library has no selection.")
        val parsed = parse(value)
        require(parsed.scripts.any { it.id == selectedId }) { "The saved voice script selection is unknown." }
        return normalize(value) to parsed.copy(selectedId = selectedId)
    }

    private fun parse(value: String): State {
        require(utf8(value).size <= MAX_MARKDOWN_BYTES) { "The voice script file must be at most 2 MiB." }
        val scripts = mutableListOf<VoiceScript>()
        val ids = mutableSetOf<String>()
        val instructions = StringBuilder()
        var activeId: String? = null
        var currentId: String? = null
        var currentName = ""

        fun finishScript() {
            val id = currentId ?: return
            val text = trim(instructions.toString())
            require(text.isNotEmpty() && text.length <= 20_000) { "Give $id instructions of 1 to 20,000 characters." }
            val revision = MessageDigest.getInstance("SHA-256").digest(utf8(text))
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            scripts += VoiceScript(id, currentName, text, revision)
            instructions.setLength(0)
        }

        normalize(value).split('\n').forEach { line ->
            if (line == "##" || line.startsWith("## ") || line.startsWith("##\t")) {
                val match = heading.matchEntire(line)
                    ?: throw IllegalArgumentException("Use script headings in the form: ## script_id | Display name.")
                finishScript()
                val id = match.groupValues[1]
                require(ids.add(id)) { "Duplicate voice script ID: $id." }
                require(ids.size <= 100) { "Keep between 1 and 100 voice scripts in the file." }
                val name = trim(match.groups[2]?.value ?: id)
                require(name.isNotEmpty() && name.length <= 80) { "Give $id a name of 1 to 80 characters." }
                currentId = id
                currentName = name
            } else if (currentId != null) {
                instructions.append(line).append('\n')
            } else if (line.startsWith("ACTIVE_VOICE_SCRIPT")) {
                val match = activeLine.matchEntire(line)
                require(activeId == null && match != null) { "Set ACTIVE_VOICE_SCRIPT = script_id once before the script sections." }
                activeId = match.groupValues[1]
            }
        }
        finishScript()
        require(scripts.isNotEmpty()) { "Keep between 1 and 100 voice scripts in the file." }
        val selectedId = requireNotNull(activeId) { "Set ACTIVE_VOICE_SCRIPT = script_id once before the script sections." }
        require(selectedId in ids) { "ACTIVE_VOICE_SCRIPT names an unknown script: $selectedId." }
        return State(ready = true, selectedId = selectedId, scripts = scripts.toList())
    }

    private fun write(value: String, selectedId: String) {
        val destination = file()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val bytes = JSONObject().put("markdown", value).put("selectedId", selectedId).toString().toByteArray(Charsets.UTF_8)
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun file() = File(checkNotNull(appContext).noBackupFilesDir, "voice-scripts.json")

    private fun normalize(value: String) = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun trim(value: String) = value.trim { it.isWhitespace() || it == '\u0085' }

    private fun utf8(value: String): ByteArray = try {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Voice scripts must contain valid UTF-8 text.")
    }

    private fun decodeUtf8(value: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Voice scripts must contain valid UTF-8 text.")
    }

    private fun Throwable.messageFor(fallback: String) = if (this is IllegalArgumentException) message ?: fallback else fallback

    private fun fail(message: String, keepWorking: Boolean = false): String {
        mutableState.value = mutableState.value.copy(working = keepWorking, error = true, message = message)
        return message
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
