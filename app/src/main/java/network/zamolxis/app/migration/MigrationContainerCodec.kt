package network.zamolxis.app.migration

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The byte-level half of [MigrationContainer]: how a chunk is addressed, how a
 * recovery key is written down, and how the payload is read back.
 *
 * Split out because the container object was doing two jobs — deciding what is
 * protected and how, and pushing bytes around — and only the first is worth
 * reading when you want to know whether the format is sound.
 */

internal const val GCM_NONCE_BYTES = 12
internal const val GCM_TAG_BITS = 128
internal const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
internal const val STREAM_NONCE_PREFIX_BYTES = 8
internal const val CONTAINER_CIPHER = "AES/GCM/NoPadding"

private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8
private const val BASE32_BITS = 5
private const val BASE32_MASK = 0x1F

/**
 * Crockford's Base32 alphabet, not RFC 4648's.
 *
 * RFC 4648 keeps I, L and O, which is exactly the failure this encoding exists
 * to avoid: someone copying a key off paper cannot tell them from 1, 1 and 0.
 * Crockford drops those three and U, and defines the substitutions a reader
 * would make anyway.
 */
private const val BASE32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/**
 * Bind a slot to its own parameters.
 *
 * Without this, an attacker could rewrite the Argon2 cost down to nothing and
 * attack the slot at a fraction of the intended price. The parameters still
 * have to be readable — they are needed to derive the key at all — but they
 * can be made unforgeable, and that is enough.
 */
internal fun slotAad(
    type: Byte,
    params: ByteArray,
): ByteArray = byteArrayOf(MigrationContainer.VERSION, type) + params

/**
 * A nonce is never reused: the prefix is fresh per container and the counter is
 * part of it. GCM's failure on nonce reuse is total, so this is not a place for
 * cleverness.
 */
internal fun chunkNonce(
    prefix: ByteArray,
    index: Long,
): ByteArray =
    ByteArray(GCM_NONCE_BYTES).also { nonce ->
        prefix.copyInto(nonce)
        var value = index
        for (i in GCM_NONCE_BYTES - 1 downTo STREAM_NONCE_PREFIX_BYTES) {
            nonce[i] = (value and BYTE_MASK.toLong()).toByte()
            value = value ushr BYTE_BITS
        }
    }

/**
 * What each chunk authenticates besides its contents: where it sits, and
 * whether it is the last. The second is what makes truncation detectable.
 */
internal fun chunkAad(
    index: Long,
    isLast: Boolean,
): ByteArray =
    ByteArray(Long.SIZE_BYTES + 2).also { aad ->
        aad[0] = MigrationContainer.VERSION
        var value = index
        for (i in Long.SIZE_BYTES downTo 1) {
            aad[i] = (value and BYTE_MASK.toLong()).toByte()
            value = value ushr BYTE_BITS
        }
        aad[Long.SIZE_BYTES + 1] = if (isLast) 1 else 0
    }

/** Read until [into] is full or the source ends; returns how much arrived. */
internal fun readFully(
    source: InputStream,
    into: ByteArray,
): Int {
    var total = 0
    while (total < into.size) {
        val read = source.read(into, total, into.size - total)
        if (read < 0) break
        total += read
    }
    return total
}

internal fun ByteArrayOutputStream.writeIntBe(value: Int) {
    write((value ushr 24) and BYTE_MASK)
    write((value ushr 16) and BYTE_MASK)
    write((value ushr BYTE_BITS) and BYTE_MASK)
    write(value and BYTE_MASK)
}

internal fun readIntBe(
    bytes: ByteArray,
    at: Int,
): Int =
    ((bytes[at].toInt() and BYTE_MASK) shl 24) or
        ((bytes[at + 1].toInt() and BYTE_MASK) shl 16) or
        ((bytes[at + 2].toInt() and BYTE_MASK) shl BYTE_BITS) or
        (bytes[at + 3].toInt() and BYTE_MASK)

internal fun base32(data: ByteArray): String {
    val out = StringBuilder()
    var buffer = 0
    var bits = 0
    data.forEach { byte ->
        buffer = (buffer shl BYTE_BITS) or (byte.toInt() and BYTE_MASK)
        bits += BYTE_BITS
        while (bits >= BASE32_BITS) {
            out.append(BASE32_ALPHABET[(buffer ushr (bits - BASE32_BITS)) and BASE32_MASK])
            bits -= BASE32_BITS
        }
    }
    if (bits > 0) out.append(BASE32_ALPHABET[(buffer shl (BASE32_BITS - bits)) and BASE32_MASK])
    return out.toString()
}

internal fun unbase32(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    text.uppercase().forEach { character ->
        if (character == '-' || character.isWhitespace()) return@forEach
        // The substitutions a person makes when reading handwriting anyway.
        val normalised =
            when (character) {
                'I', 'L' -> '1'
                'O' -> '0'
                else -> character
            }
        val value = BASE32_ALPHABET.indexOf(normalised)
        if (value < 0) throw InvalidExportFileException("Recovery key contains '$character'")
        buffer = (buffer shl BASE32_BITS) or value
        bits += BASE32_BITS
        if (bits >= BYTE_BITS) {
            out.write((buffer ushr (bits - BYTE_BITS)) and BYTE_MASK)
            bits -= BYTE_BITS
        }
    }
    return out.toByteArray()
}

/**
 * Reads the payload chunk by chunk, so a large export never has to fit in
 * memory to be imported.
 */
internal class ChunkDecryptingStream(
    private val source: DataInputStream,
    dataKey: ByteArray,
    private val noncePrefix: ByteArray,
) : InputStream() {
    private val key = SecretKeySpec(dataKey, "AES")
    private var buffer: ByteArray = ByteArray(0)
    private var offset = 0
    private var index = 0L
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(
        destination: ByteArray,
        at: Int,
        length: Int,
    ): Int {
        // Loops rather than filling once: the last chunk of a container whose
        // plaintext divides evenly into CHUNK_SIZE is empty, and returning 0
        // from read() means "nothing yet", not "end of file".
        while (offset >= buffer.size) {
            if (!fill()) return -1
        }
        val taken = minOf(length, buffer.size - offset)
        buffer.copyInto(destination, at, offset, offset + taken)
        offset += taken
        return taken
    }

    private fun fill(): Boolean {
        if (finished) return false

        val ciphertext = readChunk()

        // Which of the two it is cannot be known before trying, so both are
        // attempted; a chunk authenticates whether it is the last one, and that
        // is what makes truncation detectable at all.
        val plaintext =
            decryptChunk(ciphertext, isLast = false)
                ?: decryptChunk(ciphertext, isLast = true)?.also { finished = true }
                ?: throw InvalidExportFileException("Container payload failed authentication")

        buffer = plaintext
        offset = 0
        index++
        return true
    }

    /**
     * Both the length and the chunk itself can run off the end of a file that
     * was cut short, and either way the answer is the same: refuse. Handing
     * back what was read would be a silently truncated import, which is the
     * failure this format exists to make loud.
     */
    private fun readChunk(): ByteArray =
        try {
            val size = source.readInt()
            require(size in GCM_TAG_BYTES..(MigrationContainer.CHUNK_SIZE + GCM_TAG_BYTES)) {
                "Chunk length of $size bytes is out of range"
            }
            ByteArray(size).also(source::readFully)
        } catch (e: EOFException) {
            throw InvalidExportFileException("Container ends mid-payload", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidExportFileException(e.message ?: "Malformed chunk", e)
        }

    // A failed tag here means "not this chunk variant"; the caller tries the
    // other, and only both failing is an error worth raising.
    @Suppress("SwallowedException")
    private fun decryptChunk(
        ciphertext: ByteArray,
        isLast: Boolean,
    ): ByteArray? =
        try {
            val cipher = Cipher.getInstance(CONTAINER_CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, chunkNonce(noncePrefix, index)),
            )
            cipher.updateAAD(chunkAad(index, isLast))
            cipher.doFinal(ciphertext)
        } catch (_: javax.crypto.AEADBadTagException) {
            null
        }
}

/**
 * Generating and rendering recovery keys.
 *
 * Separate from [MigrationContainer] because none of it is about the
 * container: it is about producing a secret with enough entropy that
 * guessing is off the table, and rendering it so a human can copy it onto
 * paper without introducing an error.
 */
object RecoveryKey {
    /** 256 bits. Not guessable, which is the whole point of offering it. */
    const val LENGTH_BYTES = 32

    private const val GROUP_SIZE = 4

    /**
     * Generate a recovery key.
     *
     * Returned raw; [encode] renders it for a human to copy down.
     */
    fun generate(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(LENGTH_BYTES).also(random::nextBytes)

    /**
     * Render a recovery key as groups of Base32.
     *
     * Crockford Base32 rather than hex or Base64, because this is transcribed
     * by hand, possibly under stress, possibly from a photograph. Its alphabet
     * omits I, L, O and U, so no two characters in it look alike in a common
     * font; reading it back is case-insensitive and forgives the substitutions
     * a person makes anyway — I and L read as 1, O as 0.
     */
    fun encode(key: ByteArray): String = base32(key).chunked(GROUP_SIZE).joinToString("-")

    /** Parse a recovery key as shown by [encode]; grouping and case are ignored. */
    fun decode(text: String): ByteArray = unbase32(text)
}
