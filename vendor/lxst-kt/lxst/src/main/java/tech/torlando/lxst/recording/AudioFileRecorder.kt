package tech.torlando.lxst.recording

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records a microphone stream to a finalized Ogg Opus file.
 *
 * Callers must hold `android.permission.RECORD_AUDIO`. A unique partial sibling file is used while
 * recording, so [stop] is the only operation that can publish the requested output path.
 */
class AudioFileRecorder internal constructor(
    private val config: RecordingConfig = RecordingConfig(),
    private val backendFactory: RecorderBackendFactory,
    private val sdkInt: () -> Int,
    private val elapsedRealtimeMillis: () -> Long,
) : AutoCloseable {
    constructor(
        context: Context,
        config: RecordingConfig = RecordingConfig(),
    ) : this(
        config = config,
        backendFactory = androidRecorderBackendFactory(context),
        sdkInt = { Build.VERSION.SDK_INT },
        elapsedRealtimeMillis = SystemClock::elapsedRealtime,
    )

    private val lock = Any()
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)

    val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    private var backend: RecorderBackend? = null
    private var requestedOutput: File? = null
    private var partialOutput: File? = null
    private var startedAtMillis: Long? = null

    fun isSupported(): Boolean = sdkInt() >= Build.VERSION_CODES.Q

    fun start(outputFile: File) {
        synchronized(lock) {
            if (!isSupported()) {
                throw UnsupportedAudioRecordingException(
                    "Ogg Opus recording requires Android 10 (API 29) or newer",
                )
            }
            check(mutableState.value !is RecorderState.Recording && mutableState.value !is RecorderState.Finalizing) {
                "A recording is already active"
            }
            require(!outputFile.exists()) { "Output file already exists: $outputFile" }
            val parent = outputFile.absoluteFile.parentFile
                ?: throw AudioRecordingException("Output file has no parent directory")
            if (parent.exists() && !parent.isDirectory) {
                throw AudioRecordingException("Output parent is not a directory: $parent")
            }
            if (!parent.exists() && !parent.mkdirs()) {
                throw AudioRecordingException("Could not create output directory: $parent")
            }
            val partial =
                runCatching { File.createTempFile(".${outputFile.name}.", ".part", parent) }
                    .getOrElse { throw AudioRecordingException("Could not create partial recording", it) }

            var createdBackend: RecorderBackend? = null
            try {
                createdBackend = backendFactory.create(partial, config)
                createdBackend.start()
                val startedAt = elapsedRealtimeMillis()
                backend = createdBackend
                requestedOutput = outputFile
                partialOutput = partial
                startedAtMillis = startedAt
                mutableState.value = RecorderState.Recording(startedAt)
            } catch (error: Throwable) {
                runCatching { createdBackend?.release() }
                partial.delete()
                clearActiveRecording()
                mutableState.value = RecorderState.Failed(error)
                throw AudioRecordingException("Could not start Ogg Opus recording", error)
            }
        }
    }

    fun stop(): RecordedAudio =
        synchronized(lock) {
            val activeBackend = backend
                ?: throw IllegalStateException("No recording is active")
            val output = checkNotNull(requestedOutput)
            val partial = checkNotNull(partialOutput)
            val startedAt = checkNotNull(startedAtMillis)
            mutableState.value = RecorderState.Finalizing

            var released = false
            try {
                activeBackend.stop()
                activeBackend.release()
                released = true
                backend = null
                if (!partial.isFile || partial.length() <= 0L) {
                    throw AudioRecordingException("Recorder produced an empty output file")
                }
                synchronized(publicationLock) {
                    if (output.exists() || !partial.renameTo(output)) {
                        throw AudioRecordingException("Could not publish finalized recording: $output")
                    }
                }
                val result =
                    RecordedAudio(
                        file = output,
                        durationMillis = (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0L),
                        sizeBytes = output.length(),
                    )
                clearActiveRecording()
                mutableState.value = RecorderState.Completed(result)
                result
            } catch (error: Throwable) {
                if (!released) {
                    runCatching { activeBackend.release() }
                }
                backend = null
                partial.delete()
                clearActiveRecording()
                mutableState.value = RecorderState.Failed(error)
                if (error is AudioRecordingException) throw error
                throw AudioRecordingException("Could not finalize Ogg Opus recording", error)
            }
        }

    fun cancel() {
        synchronized(lock) {
            val activeBackend = backend ?: return
            runCatching { activeBackend.release() }
            partialOutput?.delete()
            clearActiveRecording()
            mutableState.value = RecorderState.Idle
        }
    }

    override fun close() = cancel()

    private fun clearActiveRecording() {
        backend = null
        requestedOutput = null
        partialOutput = null
        startedAtMillis = null
    }

    private companion object {
        val publicationLock = Any()
    }
}

private fun androidRecorderBackendFactory(context: Context): RecorderBackendFactory {
    val appContext = context.applicationContext
    return RecorderBackendFactory { file, settings ->
        AndroidMediaRecorderBackend(appContext, file, settings)
    }
}
