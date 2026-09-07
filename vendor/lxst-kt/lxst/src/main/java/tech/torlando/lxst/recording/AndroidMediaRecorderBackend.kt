package tech.torlando.lxst.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

internal interface RecorderBackend {
    fun start()

    fun stop()

    fun release()
}

internal fun interface RecorderBackendFactory {
    fun create(
        outputFile: File,
        config: RecordingConfig,
    ): RecorderBackend
}

internal class AndroidMediaRecorderBackend(
    context: Context,
    outputFile: File,
    config: RecordingConfig,
) : RecorderBackend {
    private val mediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context.applicationContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private var released = false

    init {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Ogg Opus recording requires Android 10 (API 29) or newer"
        }
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mediaRecorder.setAudioSamplingRate(config.sampleRateHz)
            mediaRecorder.setAudioChannels(config.channelCount)
            mediaRecorder.setAudioEncodingBitRate(config.bitRateBps)
            mediaRecorder.setOutputFile(outputFile.absolutePath)
            mediaRecorder.prepare()
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    override fun start() = mediaRecorder.start()

    override fun stop() = mediaRecorder.stop()

    override fun release() {
        if (!released) {
            released = true
            mediaRecorder.release()
        }
    }
}
