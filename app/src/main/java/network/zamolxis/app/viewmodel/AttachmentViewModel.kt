package network.zamolxis.app.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import network.zamolxis.app.data.model.ImageCompressionPreset
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.app.service.ConversationLinkManager
import network.zamolxis.app.util.FileAttachment
import network.zamolxis.app.util.FileUtils
import network.zamolxis.app.util.ImageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Everything the composer has staged but not yet sent: the image, the file
 * attachments, and the quality dialog that produces the image.
 *
 * Split out of [MessagingViewModel] because it is a different job. This class
 * answers "what is attached right now"; [MessagingViewModel] answers "put it on
 * the wire". The two used to be one object, which is why the send path could
 * reach into composer state mid-send and why the file was 3641 lines.
 *
 * The seam between them is deliberately one-directional and passes through the
 * screen:
 *
 *  - to send, the screen reads [snapshot] and hands it to
 *    `MessagingViewModel.sendMessage(...)`. The send path never reads live
 *    composer state, so an attachment staged while a send is in flight cannot
 *    be picked up half-way and shipped next to somebody else's ciphertext.
 *  - to clear, the send path reports back which instances it consumed
 *    ([ComposerSendResult.consumedAttachments]) and the screen passes them to
 *    [clearSubmitted], which compares by identity. Staging a new photo during a
 *    send therefore survives that send completing.
 *
 * Voice recordings stay in [MessagingViewModel]: they are owned by a recorder
 * with its own lifecycle and microphone lease, not by composer state.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class AttachmentViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val conversationLinkManager: ConversationLinkManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "AttachmentViewModel"

            /** How long to wait for a link probe before falling back to the saved preset. */
            private const val LINK_PROBE_TIMEOUT_MS = 5_000L
        }

        // ========== Image Attachment State ==========

        private val _selectedImageData = MutableStateFlow<ByteArray?>(null)
        val selectedImageData: StateFlow<ByteArray?> = _selectedImageData.asStateFlow()

        private val _selectedImageFormat = MutableStateFlow<String?>(null)
        val selectedImageFormat: StateFlow<String?> = _selectedImageFormat.asStateFlow()

        private val _selectedImageIsAnimated = MutableStateFlow(false)
        val selectedImageIsAnimated: StateFlow<Boolean> = _selectedImageIsAnimated.asStateFlow()

        private val _isProcessingImage = MutableStateFlow(false)
        val isProcessingImage: StateFlow<Boolean> = _isProcessingImage.asStateFlow()

        // ========== File Attachment State (LXMF Field 5) ==========

        private val _selectedFileAttachments = MutableStateFlow<List<FileAttachment>>(emptyList())
        val selectedFileAttachments: StateFlow<List<FileAttachment>> = _selectedFileAttachments.asStateFlow()

        private val _isProcessingFile = MutableStateFlow(false)
        val isProcessingFile: StateFlow<Boolean> = _isProcessingFile.asStateFlow()

        // ========== Computed State ==========

        /** Combined size of the staged file attachments, for the composer's size chip. */
        val totalAttachmentSize: StateFlow<Int> =
            _selectedFileAttachments
                .map { files -> files.sumOf { it.sizeBytes } }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = 0,
                )

        /** Whether anything is staged (image or files). Voice is tracked separately. */
        val hasAttachments: StateFlow<Boolean> =
            combine(
                _selectedImageData,
                _selectedFileAttachments,
            ) { imageData, files ->
                imageData != null || files.isNotEmpty()
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = false,
            )

        // ========== Quality Selection State ==========

        private val _qualitySelectionState = MutableStateFlow<QualitySelectionState?>(null)
        val qualitySelectionState: StateFlow<QualitySelectionState?> = _qualitySelectionState.asStateFlow()

        // Multi-image share state: URIs pending compression+send after quality selection.
        // Plain vars (not StateFlows) because they are always written before
        // _qualitySelectionState triggers recomposition, so no reactive subscription is needed.
        private var pendingSharedImageUris: List<Uri> = emptyList()
        private var pendingSharedImageDestHash: String? = null

        private val sharedImageRequests = Channel<SharedImageRequest>(Channel.BUFFERED)

        /**
         * Emitted when the user picks a quality preset for an externally shared
         * batch of images. The batch is compressed and sent by
         * [MessagingViewModel], which owns the send path — this view model only
         * asks the question and reports the answer.
         */
        val sharedImageRequest: Flow<SharedImageRequest> = sharedImageRequests.receiveAsFlow()

        // ========== Image Selection ==========

        /**
         * Stage an image for the next send.
         *
         * @param imageData the encoded image bytes
         * @param imageFormat the container format ("jpg", "png", "gif")
         * @param isAnimated true for a GIF whose animation survived, so the
         *   preview plays it instead of showing a still frame
         */
        fun selectImage(
            imageData: ByteArray,
            imageFormat: String,
            isAnimated: Boolean = false,
        ) {
            Log.d(TAG, "Selected image: ${imageData.size} bytes, format=$imageFormat, animated=$isAnimated")
            _selectedImageData.value = imageData
            _selectedImageFormat.value = imageFormat
            _selectedImageIsAnimated.value = isAnimated
        }

        fun clearSelectedImage() {
            Log.d(TAG, "Clearing selected image")
            _selectedImageData.value = null
            _selectedImageFormat.value = null
            _selectedImageIsAnimated.value = false
        }

        fun setProcessingImage(processing: Boolean) {
            _isProcessingImage.value = processing
        }

        // ========== File Attachments ==========

        /**
         * Stage a file attachment.
         *
         * File attachments have no size limit here — they are sent uncompressed,
         * and the send path rejects a payload that is too large to fit in memory.
         *
         * @param destinationHash the open conversation, or null when there is none.
         *   Intent-to-transmit: the user picking a file is an explicit action that
         *   justifies establishing the link now, so the transfer does not start
         *   with a cold path. Passed in rather than read from a shared "current
         *   conversation" so this view model owns no conversation state.
         */
        fun addFileAttachment(
            attachment: FileAttachment,
            destinationHash: String? = null,
        ) {
            viewModelScope.launch {
                val currentFiles = _selectedFileAttachments.value
                _selectedFileAttachments.value = currentFiles + attachment
                Log.d(TAG, "Added file attachment: ${attachment.filename} (${attachment.sizeBytes} bytes)")

                destinationHash?.let { conversationLinkManager.openConversationLink(it) }
            }
        }

        fun removeFileAttachment(index: Int) {
            val currentFiles = _selectedFileAttachments.value
            if (index in currentFiles.indices) {
                val removed = currentFiles[index]
                _selectedFileAttachments.value = currentFiles.toMutableList().apply { removeAt(index) }
                Log.d(TAG, "Removed file attachment: ${removed.filename}")
            }
        }

        fun clearFileAttachments() {
            Log.d(TAG, "Clearing all file attachments")
            _selectedFileAttachments.value = emptyList()
        }

        fun setProcessingFile(processing: Boolean) {
            _isProcessingFile.value = processing
        }

        /** Drop everything staged, image and files alike. */
        fun clearAllAttachments() {
            clearSelectedImage()
            clearFileAttachments()
        }

        // ========== Send hand-off ==========

        /**
         * What is staged right now, frozen for one send.
         *
         * The send path takes this by value. Reading composer state again later
         * would let an attachment the user stages mid-send join a message it was
         * never meant for.
         */
        fun snapshot(): ComposerAttachments =
            ComposerAttachments(
                imageData = _selectedImageData.value,
                imageFormat = _selectedImageFormat.value,
                files = _selectedFileAttachments.value,
            )

        /**
         * Clear exactly what a completed send consumed.
         *
         * Compared by identity, not equality: if the user staged a new photo
         * while the old one was going out, the new one must stay. Two photos can
         * easily have equal bytes; they are still different attachments.
         */
        fun clearSubmitted(consumed: ComposerAttachments) {
            if (consumed.imageData != null && _selectedImageData.value === consumed.imageData) {
                clearSelectedImage()
            }
            if (_selectedFileAttachments.value === consumed.files) {
                clearFileAttachments()
            }
        }

        // ========== Image Compression ==========

        /**
         * Open the quality selection dialog for a single image.
         *
         * The recommended preset comes from the live link when there is one, so
         * the default is what this path can actually carry, and falls back to the
         * saved preference otherwise.
         *
         * @param destinationHash the open conversation, or null when the screen
         *   has not resolved one yet — the dialog still opens, only without a
         *   link-based recommendation.
         */
        fun processImageWithCompression(
            context: Context,
            uri: Uri,
            destinationHash: String? = null,
        ) {
            viewModelScope.launch {
                Log.d(TAG, "Opening quality selection for image")

                // Trigger link establishment for speed probing when the user attaches an image
                destinationHash?.let { conversationLinkManager.openConversationLink(it) }

                val linkState = destinationHash?.let { conversationLinkManager.linkStates.value[it] }
                val recommendedPreset = recommendPreset(linkState)
                val transferTimeEstimates = calculateTransferTimeEstimates(linkState, context, uri)

                _qualitySelectionState.value =
                    QualitySelectionState(
                        imageUri = uri,
                        context = context,
                        recommendedPreset = recommendedPreset,
                        transferTimeEstimates = transferTimeEstimates,
                    )
            }
        }

        /**
         * Open one quality dialog for a batch of externally shared images.
         *
         * Unlike the single-image path this waits for the link probe (bounded by
         * [LINK_PROBE_TIMEOUT_MS]) before showing the dialog: the batch is about
         * to be sent unattended, so the transfer-time estimates the user decides
         * on had better be real.
         */
        fun processSharedImages(
            context: Context,
            uris: List<Uri>,
            destinationHash: String,
        ) {
            if (uris.isEmpty()) return

            // Dismiss any in-progress quality dialog to avoid sending the wrong images
            if (_qualitySelectionState.value != null) {
                dismissQualitySelection()
            }

            pendingSharedImageUris = uris
            pendingSharedImageDestHash = destinationHash

            viewModelScope.launch {
                conversationLinkManager.openConversationLink(destinationHash)

                val linkState =
                    withTimeoutOrNull(LINK_PROBE_TIMEOUT_MS) {
                        conversationLinkManager.linkStates
                            .map { it[destinationHash] }
                            .first { state -> state != null && !state.isEstablishing }
                    }

                val recommendedPreset = recommendPreset(linkState)
                val transferTimeEstimates = calculateTransferTimeEstimates(linkState, context, uris.first())

                _qualitySelectionState.value =
                    QualitySelectionState(
                        imageUri = uris.first(),
                        context = context,
                        recommendedPreset = recommendedPreset,
                        transferTimeEstimates = transferTimeEstimates,
                    )
            }
        }

        /**
         * How many externally shared images the open dialog is deciding for, or
         * 0 when it belongs to the composer.
         *
         * The screen needs this twice: to label the dialog ("Send 5 images") and
         * to know which of the two "user picked a preset" calls to make.
         */
        fun pendingSharedImageCount(): Int = pendingSharedImageUris.size

        /** User picked a preset — compress and stage the image. */
        fun selectImageQuality(preset: ImageCompressionPreset) {
            val state = _qualitySelectionState.value ?: return
            _qualitySelectionState.value = null

            viewModelScope.launch {
                _isProcessingImage.value = true
                try {
                    Log.d(TAG, "User selected quality: ${preset.name}")

                    val result =
                        withContext(Dispatchers.IO) {
                            ImageUtils.compressImageWithPreset(state.context, state.imageUri, preset)
                        }

                    if (result == null) {
                        Log.e(TAG, "Failed to compress image")
                        return@launch
                    }

                    Log.d(TAG, "Image compressed to ${result.compressedImage.data.size} bytes")
                    selectImage(result.compressedImage.data, result.compressedImage.format)
                } catch (e: Exception) {
                    Log.e(TAG, "Error compressing image with selected quality", e)
                } finally {
                    _isProcessingImage.value = false
                }
            }
        }

        /**
         * User picked a preset for a shared batch — hand it to the send path.
         *
         * Nothing is staged in the composer: the batch goes out as one message
         * per image, so routing it through the single-image state flows would be
         * a race with whatever the user has already typed.
         */
        fun selectImageQualityForSharedImages(preset: ImageCompressionPreset) {
            val state = _qualitySelectionState.value ?: return
            val uris = pendingSharedImageUris.toList()
            val destHash = pendingSharedImageDestHash ?: return

            _qualitySelectionState.value = null
            pendingSharedImageUris = emptyList()
            pendingSharedImageDestHash = null

            viewModelScope.launch {
                sharedImageRequests.send(
                    SharedImageRequest(
                        context = state.context,
                        uris = uris,
                        destinationHash = destHash,
                        preset = preset,
                    ),
                )
            }
        }

        /** Close the dialog without choosing, discarding any pending shared batch. */
        fun dismissQualitySelection() {
            Log.d(TAG, "Dismissing quality selection dialog")
            _qualitySelectionState.value = null
            pendingSharedImageUris = emptyList()
            pendingSharedImageDestHash = null
        }

        private suspend fun recommendPreset(linkState: ConversationLinkManager.LinkState?): ImageCompressionPreset =
            if (linkState != null && linkState.isActive) {
                linkState.recommendPreset()
            } else {
                // No active link - use saved preset or default to MEDIUM
                val savedPreset = settingsRepository.getImageCompressionPreset()
                if (savedPreset == ImageCompressionPreset.AUTO) {
                    ImageCompressionPreset.MEDIUM
                } else {
                    savedPreset
                }
            }

        /**
         * Transfer time estimates for each preset based on link state.
         *
         * For ORIGINAL preset, uses actual file size since it applies minimal compression.
         * For other presets, uses the target size (worst-case estimate).
         */
        private fun calculateTransferTimeEstimates(
            linkState: ConversationLinkManager.LinkState?,
            context: Context,
            imageUri: Uri,
        ): Map<ImageCompressionPreset, String?> {
            val actualFileSize = FileUtils.getFileSize(context, imageUri)

            return listOf(
                ImageCompressionPreset.LOW,
                ImageCompressionPreset.MEDIUM,
                ImageCompressionPreset.HIGH,
                ImageCompressionPreset.ORIGINAL,
            ).associateWith { preset ->
                val sizeBytes =
                    if (preset == ImageCompressionPreset.ORIGINAL && actualFileSize > 0) {
                        actualFileSize
                    } else {
                        preset.targetSizeBytes
                    }
                linkState?.estimateTransferTimeFormatted(sizeBytes)
            }
        }
    }

/**
 * The attachments one send was handed, frozen at the moment the user hit send.
 *
 * Deliberately not a data class: [AttachmentViewModel.clearSubmitted] compares
 * these by identity, and generated equality over a `ByteArray` would compare by
 * identity in one field and by value in another — the worst of both.
 */
class ComposerAttachments(
    val imageData: ByteArray? = null,
    val imageFormat: String? = null,
    val files: List<FileAttachment> = emptyList(),
) {
    val isEmpty: Boolean get() = imageData == null && files.isEmpty()

    companion object {
        val NONE = ComposerAttachments()
    }
}

/**
 * A batch of externally shared images the user has approved a quality for.
 *
 * @property preset the compression the user chose, applied to every image
 */
data class SharedImageRequest(
    val context: Context,
    val uris: List<Uri>,
    val destinationHash: String,
    val preset: ImageCompressionPreset,
)

/**
 * State for the image quality selection dialog.
 *
 * @property imageUri the image to compress, or the first of a shared batch
 * @property context Android context for compression operations
 * @property recommendedPreset the preset recommended for the current link
 * @property transferTimeEstimates how long each preset would take on this link,
 *   or null entries when there is no link to estimate from
 */
data class QualitySelectionState(
    val imageUri: Uri,
    val context: Context,
    val recommendedPreset: ImageCompressionPreset,
    val transferTimeEstimates: Map<ImageCompressionPreset, String?>,
)
