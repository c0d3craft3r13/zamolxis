package network.zamolxis.app.service.pq

import network.zamolxis.app.rns.api.util.LxmfFields
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePackException

/**
 * Everything the hybrid layer puts *inside* the seal.
 *
 * The sealed blob replaces the LXMF fields it carries, so it is keyed by the
 * same field numbers those fields would have used, with `0` for the message
 * text. A receiver decodes it and rebuilds exactly the fields the sender
 * removed — see [toLxmfFields].
 *
 * @property content the message text
 * @property image `[format, bytes]` — LXMF [LxmfFields.FIELD_IMAGE]
 * @property files `[[name, bytes], …]` — LXMF [LxmfFields.FIELD_FILE_ATTACHMENTS]
 * @property audio `[mode, bytes]` — LXMF [LxmfFields.FIELD_AUDIO]
 * @property replyQuote the quoted text of the message being replied to —
 *   LXMF [LxmfFields.FIELD_REPLY_QUOTE]. Sealed with the rest because it is the
 *   *content* of an earlier message; leaving it outside would publish in the
 *   clear the very text the original send took care to seal.
 * @property group the group-chat envelope (`GroupWireCodec.toMap` output), or
 *   null for direct messages. Carried under [LxmfFields.FIELD_CUSTOM_META] — an
 *   arbitrary nested map with String/Number/Boolean/List/Map leaves. Group
 *   membership is as confidential as the text, so it seals with the rest.
 */
class SealedPayload(
    val content: String,
    val image: Image? = null,
    val files: List<FileAttachment> = emptyList(),
    val audio: Audio? = null,
    val replyQuote: String? = null,
    val group: Map<String, Any>? = null,
) {
    /** An image attachment: LXMF format tag plus the encoded bytes. */
    class Image(
        val format: String,
        val bytes: ByteArray,
    )

    /** A file attachment: filename plus contents. */
    class FileAttachment(
        val name: String,
        val bytes: ByteArray,
    )

    /** A voice note: LXMF audio-mode value plus the encoded bytes. */
    class Audio(
        val mode: Int,
        val bytes: ByteArray,
    )

    /** Whether anything beyond the text is being carried. */
    val hasAttachments: Boolean
        get() = image != null || files.isNotEmpty() || audio != null

    /** Total attachment bytes, which is what decides whether sealing is affordable. */
    val attachmentBytes: Long
        get() =
            (image?.bytes?.size?.toLong() ?: 0L) +
                files.sumOf { it.bytes.size.toLong() } +
                (audio?.bytes?.size?.toLong() ?: 0L)

    /**
     * The LXMF fields this payload stands in for, in the shape
     * `AppDataParser.serializeFieldsToJson` expects.
     *
     * Handing the map to that serializer rather than writing the JSON here is
     * deliberate: it is the same function both backends use for fields that
     * arrive unsealed, so a rebuilt attachment is byte-identical to one that was
     * never sealed and the UI cannot tell them apart.
     */
    fun toLxmfFields(): Map<Int, Any> =
        buildMap {
            image?.let { put(LxmfFields.FIELD_IMAGE, listOf(it.format, it.bytes)) }
            if (files.isNotEmpty()) {
                put(LxmfFields.FIELD_FILE_ATTACHMENTS, files.map { listOf(it.name, it.bytes) })
            }
            audio?.let { put(LxmfFields.FIELD_AUDIO, listOf(it.mode, it.bytes)) }
            replyQuote?.let { put(LxmfFields.FIELD_REPLY_QUOTE, it.toByteArray(Charsets.UTF_8)) }
            group?.let { put(LxmfFields.FIELD_CUSTOM_META, mapOf(LxmfFields.CUSTOM_META_KEY_GROUP to it)) }
        }
}

/** Raised when a sealed payload cannot be decoded. */
class PqPayloadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * msgpack codec for [SealedPayload].
 *
 * msgpack rather than an invented format because LXMF fields are already
 * msgpack on the wire, so the sealed blob is the same encoding one layer in —
 * nothing new to get wrong, and a reader that meets an unknown key skips it
 * instead of failing, leaving room to seal more later.
 */
object PqSealedPayloadCodec {
    /** Key for the message text. Zero cannot collide: LXMF fields start at 0x01. */
    private const val KEY_CONTENT = 0

    /**
     * Ceiling on a decoded string or blob, applied before allocating.
     *
     * The blob has already been authenticated by the time it is decoded, so this
     * is not a defence against an attacker — it is a defence against a bug on
     * the other side turning into an OutOfMemoryError on this one.
     */
    private const val MAX_ELEMENT_BYTES = 64 * 1024 * 1024

    /** Sanity ceiling on the number of file attachments in one message. */
    private const val MAX_FILES = 64

    /**
     * Bounds for the generic nested-map codec used for the group envelope.
     *
     * Unlike the fixed-shape attachment fields, this value's structure is not
     * pinned here — the group codec owns it — so the decoder applies its own
     * ceilings in the spirit of [MAX_ELEMENT_BYTES]: a malformed nesting can
     * exhaust neither the stack nor the heap. 1 MiB leaves generous room for a
     * roster sync while staying far below anything an LXMF message should carry.
     */
    private const val MAX_META_DEPTH = 8
    private const val MAX_META_ELEMENTS = 4096
    private const val MAX_META_ELEMENT_BYTES = 1024 * 1024

    fun encode(payload: SealedPayload): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        val entries =
            1 +
                (if (payload.image != null) 1 else 0) +
                (if (payload.files.isNotEmpty()) 1 else 0) +
                (if (payload.audio != null) 1 else 0) +
                (if (payload.replyQuote != null) 1 else 0) +
                (if (payload.group != null) 1 else 0)

        packer.packMapHeader(entries)

        packer.packInt(KEY_CONTENT)
        packer.packString(payload.content)

        payload.image?.let { image ->
            packer.packInt(LxmfFields.FIELD_IMAGE)
            packer.packArrayHeader(2)
            packer.packString(image.format)
            packer.packBinaryHeader(image.bytes.size)
            packer.writePayload(image.bytes)
        }

        if (payload.files.isNotEmpty()) {
            packer.packInt(LxmfFields.FIELD_FILE_ATTACHMENTS)
            packer.packArrayHeader(payload.files.size)
            payload.files.forEach { file ->
                packer.packArrayHeader(2)
                packer.packString(file.name)
                packer.packBinaryHeader(file.bytes.size)
                packer.writePayload(file.bytes)
            }
        }

        payload.audio?.let { audio ->
            packer.packInt(LxmfFields.FIELD_AUDIO)
            packer.packArrayHeader(2)
            packer.packInt(audio.mode)
            packer.packBinaryHeader(audio.bytes.size)
            packer.writePayload(audio.bytes)
        }

        payload.replyQuote?.let { quote ->
            packer.packInt(LxmfFields.FIELD_REPLY_QUOTE)
            packer.packString(quote)
        }

        payload.group?.let { group ->
            packer.packInt(LxmfFields.FIELD_CUSTOM_META)
            packer.packMetaValue(group, 0)
        }

        return packer.toByteArray()
    }

    /**
     * Decode a blob produced by [encode].
     *
     * @throws PqPayloadException if it is not one. The caller has already
     *   verified the GCM tag, so a failure here means the two sides disagree
     *   about the format rather than that someone tampered with it — worth
     *   surfacing as its own thing.
     */
    @Suppress("ThrowsCount")
    fun decode(blob: ByteArray): SealedPayload =
        try {
            decodeChecked(blob)
        } catch (e: MessagePackException) {
            throw PqPayloadException("Sealed payload is not valid msgpack", e)
        } catch (e: IllegalArgumentException) {
            throw PqPayloadException(e.message ?: "Malformed sealed payload", e)
        }

    private fun decodeChecked(blob: ByteArray): SealedPayload {
        val unpacker = MessagePack.newDefaultUnpacker(blob)
        val entries = unpacker.unpackMapHeader()

        var content: String? = null
        var image: SealedPayload.Image? = null
        var files: List<SealedPayload.FileAttachment> = emptyList()
        var audio: SealedPayload.Audio? = null
        var replyQuote: String? = null
        var group: Map<String, Any>? = null

        repeat(entries) {
            when (unpacker.unpackInt()) {
                KEY_CONTENT -> content = unpacker.unpackString()
                LxmfFields.FIELD_IMAGE -> image = unpacker.readImage()
                LxmfFields.FIELD_FILE_ATTACHMENTS -> files = unpacker.readFiles()
                LxmfFields.FIELD_AUDIO -> audio = unpacker.readAudio()
                LxmfFields.FIELD_REPLY_QUOTE -> replyQuote = unpacker.unpackString()
                LxmfFields.FIELD_CUSTOM_META -> group = unpacker.readMetaMap(0)
                // Unknown key: skip the value and carry on, so a newer sender can
                // seal something this build has never heard of without the message
                // becoming unreadable.
                else -> unpacker.skipValue()
            }
        }

        return SealedPayload(
            content = content ?: throw PqPayloadException("Sealed payload carries no content field"),
            image = image,
            files = files,
            audio = audio,
            replyQuote = replyQuote,
            group = group,
        )
    }

    private fun org.msgpack.core.MessageUnpacker.readImage(): SealedPayload.Image {
        require(unpackArrayHeader() == 2) { "Image must be [format, bytes]" }
        val format = unpackString()
        return SealedPayload.Image(format = format, bytes = readBlob())
    }

    private fun org.msgpack.core.MessageUnpacker.readFiles(): List<SealedPayload.FileAttachment> {
        val count = unpackArrayHeader()
        require(count in 0..MAX_FILES) { "Implausible attachment count" }
        return List(count) {
            require(unpackArrayHeader() == 2) { "File must be [name, bytes]" }
            val name = unpackString()
            SealedPayload.FileAttachment(name = name, bytes = readBlob())
        }
    }

    private fun org.msgpack.core.MessageUnpacker.readAudio(): SealedPayload.Audio {
        require(unpackArrayHeader() == 2) { "Audio must be [mode, bytes]" }
        val mode = unpackInt()
        return SealedPayload.Audio(mode = mode, bytes = readBlob())
    }

    private fun org.msgpack.core.MessageUnpacker.readBlob(): ByteArray {
        val length = unpackBinaryHeader()
        require(length in 0..MAX_ELEMENT_BYTES) { "Implausible attachment length" }
        return readPayload(length)
    }

    /**
     * Pack an arbitrary nested metadata value (the group envelope). Leaves are
     * String / Number / Boolean / ByteArray, containers are List and Map with
     * string keys — anything else is a caller bug and rejected loudly rather
     * than silently coerced into a shape the other side cannot parse back.
     */
    private fun org.msgpack.core.MessagePacker.packMetaValue(
        value: Any?,
        depth: Int,
    ) {
        require(depth <= MAX_META_DEPTH) { "Meta value nested too deep" }
        when (value) {
            null -> packNil()
            is String -> packString(value)
            is Boolean -> packBoolean(value)
            is Int -> packInt(value)
            is Long -> packLong(value)
            is ByteArray -> {
                packBinaryHeader(value.size)
                writePayload(value)
            }
            is List<*> -> {
                packArrayHeader(value.size)
                value.forEach { packMetaValue(it, depth + 1) }
            }
            is Map<*, *> -> {
                packMapHeader(value.size)
                value.forEach { (k, v) ->
                    packString(k.toString())
                    packMetaValue(v, depth + 1)
                }
            }
            else -> throw IllegalArgumentException("Unsupported meta value type: ${value.javaClass}")
        }
    }

    /**
     * Read what [packMetaValue] wrote, bounded against [MAX_META_DEPTH],
     * [MAX_META_ELEMENTS] and [MAX_META_ELEMENT_BYTES] before any allocation.
     * Integers come back as Long — msgpack's wire type does not distinguish
     * the widths — so consumers (e.g. `GroupWireCodec.fromMap`) must accept
     * Number rather than a concrete width.
     */
    private fun org.msgpack.core.MessageUnpacker.readMetaValue(depth: Int): Any? {
        require(depth <= MAX_META_DEPTH) { "Meta value nested too deep" }
        return when (nextFormat.valueType) {
            org.msgpack.value.ValueType.NIL -> {
                unpackNil()
                null
            }
            org.msgpack.value.ValueType.BOOLEAN -> unpackBoolean()
            org.msgpack.value.ValueType.INTEGER -> unpackLong()
            org.msgpack.value.ValueType.STRING -> {
                val length = unpackRawStringHeader()
                require(length in 0..MAX_META_ELEMENT_BYTES) { "Implausible meta string length" }
                String(readPayload(length), Charsets.UTF_8)
            }
            org.msgpack.value.ValueType.BINARY -> {
                val length = unpackBinaryHeader()
                require(length in 0..MAX_META_ELEMENT_BYTES) { "Implausible meta blob length" }
                readPayload(length)
            }
            org.msgpack.value.ValueType.ARRAY -> {
                val count = unpackArrayHeader()
                require(count in 0..MAX_META_ELEMENTS) { "Implausible meta array length" }
                List(count) { readMetaValue(depth + 1) }
            }
            org.msgpack.value.ValueType.MAP -> readMetaMap(depth)
            else -> throw IllegalArgumentException("Unsupported meta value in sealed payload")
        }
    }

    private fun org.msgpack.core.MessageUnpacker.readMetaMap(depth: Int): Map<String, Any> {
        require(depth <= MAX_META_DEPTH) { "Meta value nested too deep" }
        val count = unpackMapHeader()
        require(count in 0..MAX_META_ELEMENTS) { "Implausible meta map length" }
        val map = LinkedHashMap<String, Any>()
        repeat(count) {
            val keyLength = unpackRawStringHeader()
            require(keyLength in 0..MAX_META_ELEMENT_BYTES) { "Implausible meta key length" }
            val key = String(readPayload(keyLength), Charsets.UTF_8)
            // A nil leaf is dropped rather than stored as null: SealedPayload.group
            // is declared Map<String, Any>, and every consumer treats an absent key
            // and a null one the same way (both fail the `as?` check and reject the
            // shape). Keeping the map honestly non-null beats casting the lie away.
            readMetaValue(depth + 1)?.let { map[key] = it }
        }
        return map
    }
}
