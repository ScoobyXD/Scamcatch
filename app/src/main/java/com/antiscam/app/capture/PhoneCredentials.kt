package com.antiscam.app.capture

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only keys entered on the phone can opt into encrypted, device-local storage. */
object PhoneCredentials {
    enum class Provider(val label: String, internal val id: String) {
        JEV("Jev / TypeSafe", "jev"),
        OPENAI("OpenAI voice", "openai");

        val available: Boolean get() = when (this) {
            JEV -> JevAnalysis.available
            OPENAI -> LiveVoice.available
        }

        internal val revision: Long get() = when (this) {
            JEV -> JevAnalysis.keyRevision
            OPENAI -> LiveVoice.keyRevision
        }

        fun ready(state: CaptureState): Boolean = when (this) {
            JEV -> state.jevReady
            OPENAI -> state.voiceReady
        }

        internal fun set(value: String): Boolean = when (this) {
            JEV -> JevAnalysis.setKey(value)
            OPENAI -> LiveVoice.setKey(value)
        }

        internal fun clear() = when (this) {
            JEV -> JevAnalysis.clearKey()
            OPENAI -> LiveVoice.clearKey()
        }
    }

    data class Status(
        val working: Boolean = false,
        val saved: Boolean? = null,
        val message: String? = null,
        val error: Boolean = false,
    )

    private class Operation {
        val storageLock = Mutex()
        var revision = 0L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Provider.entries.associateWith { Operation() }
    private val mutableStatus = MutableStateFlow(Provider.entries.associateWith { Status() })
    val status = mutableStatus.asStateFlow()
    private var directory: File? = null

    fun initialize(context: Context) {
        checkMainThread()
        if (directory != null) return
        directory = context.applicationContext.noBackupFilesDir
        Provider.entries.filter { it.available }.forEach { restore(it) }
    }

    fun setKey(provider: Provider, value: String, remember: Boolean): Boolean {
        checkMainThread()
        if (directory == null || !provider.available) {
            update(provider, Status(error = true, message = "Key setup is unavailable."))
            return false
        }
        if (!provider.set(value)) {
            update(provider, mutableStatus.value.getValue(provider).copy(
                error = true, message = "Enter a valid API key without spaces or line breaks.",
            ))
            return false
        }
        changeStorage(provider, if (remember) value.trim() else null, clearing = false)
        return true
    }

    fun clearKey(provider: Provider) {
        checkMainThread()
        provider.clear()
        changeStorage(provider, null, clearing = true)
    }

    private fun restore(provider: Provider) {
        val operation = operations.getValue(provider)
        val operationRevision = operation.revision
        val providerRevision = provider.revision
        update(provider, Status(working = true, message = "Checking for a saved key…"))
        scope.launch {
            operation.storageLock.withLock {
                if (operation.revision != operationRevision) return@withLock
                val result = withContext(Dispatchers.IO) { runCatching { read(provider) } }
                if (operation.revision != operationRevision) return@withLock
                result.fold(
                    onSuccess = { key ->
                        if (provider.revision != providerRevision || provider.ready(CaptureStore.state.value)) {
                            update(provider, Status(saved = key != null))
                        } else if (key == null) {
                            update(provider, Status(saved = false))
                        } else if (provider.set(key)) {
                            update(provider, Status(saved = true, message = "Saved key loaded."))
                        } else {
                            update(provider, Status(saved = true, error = true, message = "The saved key is invalid. Clear it and enter a new key."))
                        }
                    },
                    onFailure = {
                        update(provider, Status(error = true, message = "The saved key could not be loaded. Clear it and enter a new key."))
                    },
                )
            }
        }
    }

    private fun changeStorage(provider: Provider, key: String?, clearing: Boolean) {
        val operation = operations.getValue(provider)
        val revision = ++operation.revision
        update(provider, mutableStatus.value.getValue(provider).copy(
            working = true, error = false,
            message = when {
                key != null -> "Saving encrypted key…"
                clearing -> "Active key cleared. Removing saved key…"
                else -> "Removing saved key…"
            },
        ))
        scope.launch {
            operation.storageLock.withLock {
                if (operation.revision != revision) return@withLock
                val result = withContext(Dispatchers.IO) {
                    runCatching { if (key == null) remove(provider) else write(provider, key) }
                }
                if (operation.revision != revision) return@withLock
                update(provider, if (result.isSuccess) {
                    Status(saved = key != null, message = when {
                        key != null -> "Key saved on this phone."
                        else -> "No saved copy remains."
                    })
                } else {
                    Status(error = true, message = when {
                        key != null -> "Saving failed. Any earlier saved key may remain."
                        else -> "Removing the saved copy failed. Press Clear again."
                    })
                })
            }
        }
    }

    private fun update(provider: Provider, value: Status) {
        mutableStatus.value = mutableStatus.value + (provider to value)
    }

    private fun file(provider: Provider) = File(checkNotNull(directory), "phone-key-${provider.id}.enc")
    private fun temporaryFile(provider: Provider) = File(checkNotNull(directory), "phone-key-${provider.id}.tmp")
    private fun alias(provider: Provider) = "com.antiscam.phone-key.${provider.id}.v1"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(provider: Provider): SecretKey {
        val existing = keyStore().getKey(alias(provider), null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias(provider), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun write(provider: Provider, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(provider))
        cipher.updateAAD(alias(provider).toByteArray(Charsets.UTF_8))
        val plaintext = value.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        check(cipher.iv.size == 12)
        val temporary = temporaryFile(provider)
        temporary.outputStream().use { output ->
            output.write(1)
            output.write(cipher.iv)
            output.write(encrypted)
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file(provider).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun read(provider: Provider): String? {
        val source = file(provider)
        if (!source.exists()) return null
        check(source.length() in 30L..4096L)
        val encrypted = source.readBytes()
        check(encrypted[0] == 1.toByte())
        val key = checkNotNull(keyStore().getKey(alias(provider), null) as? SecretKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(alias(provider).toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(encrypted, 13, encrypted.size - 13)
        return try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
    }

    private fun remove(provider: Provider) {
        // Delete the wrapping key first so an undeleted ciphertext cannot restore a cleared key.
        keyStore().deleteEntry(alias(provider))
        Files.deleteIfExists(file(provider).toPath())
        Files.deleteIfExists(temporaryFile(provider).toPath())
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
