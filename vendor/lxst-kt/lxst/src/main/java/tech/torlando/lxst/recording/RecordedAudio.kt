package tech.torlando.lxst.recording

import java.io.File

/** Encoding produced by [AudioFileRecorder]. */
enum class AudioFileFormat(
    val mimeType: String,
    val fileExtension: String,
) {
    OGG_OPUS("audio/ogg", "ogg"),
}

/** Voice-oriented recorder settings. */
data class RecordingConfig(
    val sampleRateHz: Int = 48_000,
    val channelCount: Int = 1,
    val bitRateBps: Int = 24_000,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channelCount == 1 || channelCount == 2) { "channelCount must be 1 or 2" }
        require(bitRateBps > 0) { "bitRateBps must be positive" }
    }
}

/** A completely finalized recording that is safe for callers to read. */
data class RecordedAudio(
    val file: File,
    val durationMillis: Long,
    val sizeBytes: Long,
    val format: AudioFileFormat = AudioFileFormat.OGG_OPUS,
)

sealed interface RecorderState {
    data object Idle : RecorderState

    data class Recording(
        val startedAtElapsedRealtimeMillis: Long,
    ) : RecorderState

    data object Finalizing : RecorderState

    data class Completed(
        val recording: RecordedAudio,
    ) : RecorderState

    data class Failed(
        val cause: Throwable,
    ) : RecorderState
}

class AudioRecordingException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class UnsupportedAudioRecordingException(
    message: String,
) : UnsupportedOperationException(message)
