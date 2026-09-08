package network.zamolxis.app.migration

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The export container, version 3.
 *
 * ## Why a new format
 *
 * Version 2 derived the file's key from the password with PBKDF2 and nothing
 * else, which made the strength of an export exactly the strength of whatever
 * the user typed. PBKDF2 is cheap on a GPU — it needs almost no memory, so it
 * parallelises beautifully — and a human-chosen password falls to a dictionary
 * attack in hours. The file is static and the attacker has forever.
 *
 * Two changes fix that, and neither is about the cipher: AES-256-GCM was never
 * the weak part, and is not weakened by quantum computers in any way that
 * matters — Grover's algorithm leaves a 256-bit key at 128-bit strength.
 *
 * **Argon2id** replaces PBKDF2. It is memory-hard: the cost is bounded by
 * memory bandwidth rather than arithmetic, so a graphics card runs tens of
 * guesses in parallel instead of tens of thousands, and an ASIC buys its
 * attacker very little. Roughly three orders of magnitude, which turns hours
 * into months.
 *
 * **Slots** remove the password's monopoly. One random 256-bit data key
 * encrypts the payload; every slot holds that same key wrapped a different
 * way, and any single slot opens the file. A recovery key slot has 2²⁵⁶
 * possibilities and so cannot be guessed at all — a user who writes one down
 * is simply not exposed to password cracking. The design is the one LUKS and
 * age use, for the same reason.
 *
 * ## Layout
 *
 * ```
 * [1]  version = 0x03
 * [1]  slot count
 * repeated per slot:
 *   [1]  slot type
 *   [2]  body length, big-endian
 *   [N]  slot body
 * [8]  stream nonce prefix
 * repeated per chunk:
 *   [4]  ciphertext length, big-endian
 *   [N]  AES-256-GCM ciphertext including its 16-byte tag
 * ```
 *
 * ## What is bound to what
 *
 * Each slot authenticates its own parameters as AAD, so the salt or the Argon2
 * cost cannot be edited to make a slot cheaper to attack. Chunks authenticate
 * their index and whether they are the last one, which stops both reordering
 * and truncation: a file cut short ends without the final marker and is
 * rejected rather than silently yielding a prefix.
 *
 * The payload is deliberately *not* bound to the whole header. Binding it
 * would freeze the slot list, and adding a recovery key to a container that
 * already exists is a thing users should be able to do. Nothing is lost by
 * leaving it out: the data key is random and reachable only through a slot, so
 * a forged header simply fails to produce a key that decrypts anything.
 */
object MigrationContainer {
    /** Version byte at the head of a v3 container. */
    const val VERSION: Byte = 0x03

    /** Plaintext bytes per chunk. Small enough to stream, large enough that the per-chunk tag is noise. */
    const val CHUNK_SIZE = 64 * 1024

    private const val SLOT_PASSWORD: Byte = 1
    private const val SLOT_RECOVERY: Byte = 2

    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val WRAPPED_KEY_BYTES = KEY_BYTES + GCM_TAG_BYTES

    /**
     * Argon2id cost, stored in every password slot so a file written today
     * still opens on hardware that has not been built yet.
     *
     * 128 MiB is chosen to be defensible on a mid-range phone rather than
     * maximal: a parameter set that kills the app on a 2 GB device would push
     * users back to weaker exports, and an export nobody can make protects
     * nothing. A device that cannot afford even this should fall back to
     * [MINIMUM_COST]; whatever it uses is written into the slot, so the file
     * stays readable either way.
     */
    val DEFAULT_COST = Argon2Cost(memoryKib = 128 * 1024, iterations = 3, parallelism = 4)

    /** The floor. Below this the KDF stops being meaningfully better than PBKDF2. */
    val MINIMUM_COST = Argon2Cost(memoryKib = 32 * 1024, iterations = 3, parallelism = 2)

    /** Argon2id parameters, carried in the file so they survive the device that chose them. */
    data class Argon2Cost(
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    )

    /** A way of opening a container. Any one of them is enough. */
    sealed interface Unlock {
        /** What the user types. Cheap to remember, expensive to attack — hence Argon2id. */
        data class Password(
            val password: String,
        ) : Unlock

        /** What the user writes down. Not guessable, so it needs no stretching. */
        data class Recovery(
            val key: ByteArray,
        ) : Unlock {
            init {
                require(key.size == RecoveryKey.LENGTH_BYTES) {
                    "recovery key must be $RecoveryKey.LENGTH_BYTES bytes, was ${key.size}"
                }
            }

            override fun equals(other: Any?): Boolean = this === other || (other is Recovery && key.contentEquals(other.key))

            override fun hashCode(): Int = key.contentHashCode()
        }
    }

    /**
     * Write [plaintext] into [sink] as a v3 container that any of [unlocks] opens.
     *
     * Streams: neither the plaintext nor the ciphertext is ever held whole, so
     * an export never has to exist unencrypted anywhere — not in memory and,
     * more importantly, not as a file that gets overwritten afterwards, where
     * flash wear levelling can leave the original blocks readable.
     */
    fun seal(
        plaintext: InputStream,
        sink: OutputStream,
        unlocks: List<Unlock>,
        cost: Argon2Cost = DEFAULT_COST,
        random: SecureRandom = SecureRandom(),
    ) {
        require(unlocks.isNotEmpty()) { "a container with no slots could never be opened" }
        require(unlocks.size <= MAX_SLOTS) { "at most $MAX_SLOTS slots" }

        val dataKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            val slots = unlocks.map { buildSlot(it, dataKey, cost, random) }
            val streamNoncePrefix = ByteArray(STREAM_NONCE_PREFIX_BYTES).also(random::nextBytes)

            val out = DataOutputStream(sink)
            out.writeByte(VERSION.toInt())
            out.writeByte(slots.size)
            slots.forEach { slot ->
                out.writeByte(slot.type.toInt())
                out.writeShort(slot.body.size)
                out.write(slot.body)
            }
            out.write(streamNoncePrefix)

            writeChunks(plaintext, out, dataKey, streamNoncePrefix)
            out.flush()
        } finally {
            dataKey.fill(0)
        }
    }

    /**
     * Open a v3 container, returning a stream over its plaintext.
     *
     * @throws WrongPasswordException when no slot of the matching kind accepts
     *   the key. Deliberately the same exception for a wrong password and a
     *   wrong recovery key: which slots a container carries is not something
     *   the failure message should disclose.
     * @throws InvalidExportFileException when the bytes are not a v3 container.
     */
    fun open(
        source: InputStream,
        unlock: Unlock,
    ): InputStream {
        val input = DataInputStream(source)
        val header = readHeader(input)

        val dataKey =
            header.slots.firstNotNullOfOrNull { slot -> tryUnwrap(slot, unlock) }
                ?: throw WrongPasswordException("No slot accepted the supplied key")

        return ChunkDecryptingStream(input, dataKey, header.streamNoncePrefix)
    }

    /** Convenience wrapper over [seal] for callers holding the whole plaintext already. */
    fun seal(
        plaintext: ByteArray,
        unlocks: List<Unlock>,
        cost: Argon2Cost = DEFAULT_COST,
        random: SecureRandom = SecureRandom(),
    ): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                seal(plaintext.inputStream(), out, unlocks, cost, random)
            }.toByteArray()

    /** Convenience wrapper over [open] for callers that want the bytes back. */
    fun open(
        container: ByteArray,
        unlock: Unlock,
    ): ByteArray = open(container.inputStream(), unlock).use { it.readBytes() }

    // ── slots ────────────────────────────────────────────────────────────────

    private class Header(
        val slots: List<Slot>,
        val streamNoncePrefix: ByteArray,
    )

    /**
     * `require` inside [parseHeader] keeps the bounds checks readable; callers
     * should still only ever see this file's own exception types.
     */
    private fun readHeader(input: DataInputStream): Header =
        try {
            parseHeader(input)
        } catch (e: IllegalArgumentException) {
            throw InvalidExportFileException(e.message ?: "Malformed container header", e)
        } catch (e: java.io.EOFException) {
            throw InvalidExportFileException("Container header is incomplete", e)
        }

    /**
     * Parse everything before the payload.
     *
     * Bounds are checked here rather than trusted, because every length in the
     * header is attacker-controlled the moment the file leaves the device: a
     * slot claiming to be four gigabytes long should be a rejection, not an
     * allocation.
     */
    private fun parseHeader(input: DataInputStream): Header {
        val version = input.read()
        require(version == VERSION.toInt()) { "Not a version 3 container (first byte: $version)" }

        val slotCount = input.read()
        require(slotCount in 1..MAX_SLOTS) { "Implausible slot count: $slotCount" }

        val slots =
            List(slotCount) {
                val type = input.readByte()
                val length = input.readUnsignedShort()
                require(length in 1..MAX_SLOT_BODY) { "Slot body of $length bytes is out of range" }
                Slot(type, ByteArray(length).also(input::readFully))
            }

        return Header(slots, ByteArray(STREAM_NONCE_PREFIX_BYTES).also(input::readFully))
    }

    private class Slot(
        val type: Byte,
        val body: ByteArray,
    )

    private fun buildSlot(
        unlock: Unlock,
        dataKey: ByteArray,
        cost: Argon2Cost,
        random: SecureRandom,
    ): Slot {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)

        val params =
            when (unlock) {
                is Unlock.Password ->
                    ByteArrayOutputStream()
                        .apply {
                            write(salt)
                            writeIntBe(cost.memoryKib)
                            write(cost.iterations)
                            write(cost.parallelism)
                            write(nonce)
                        }.toByteArray()

                is Unlock.Recovery ->
                    ByteArrayOutputStream()
                        .apply {
                            write(salt)
                            write(nonce)
                        }.toByteArray()
            }

        val type = if (unlock is Unlock.Password) SLOT_PASSWORD else SLOT_RECOVERY
        val kek = deriveKek(unlock, salt, cost)
        try {
            val cipher = Cipher.getInstance(CONTAINER_CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(slotAad(type, params))
            val wrapped = cipher.doFinal(dataKey)
            return Slot(type, params + wrapped)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Try one slot against one key, returning the data key or null.
     *
     * Null rather than an exception because a container legitimately holds
     * slots this key was never meant to open; only exhausting all of them is
     * a failure.
     *
     * A failed authentication tag is the expected outcome for every slot this
     * key was not meant to open, and is deliberately indistinguishable from a
     * wrong password: nothing about which it was may reach the caller.
     */
    @Suppress("SwallowedException")
    private fun tryUnwrap(
        slot: Slot,
        unlock: Unlock,
    ): ByteArray? {
        val parsed = parseSlot(slot, unlock) ?: return null
        val kek = deriveKek(unlock, parsed.salt, parsed.cost)
        return try {
            val cipher = Cipher.getInstance(CONTAINER_CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, parsed.nonce))
            cipher.updateAAD(slotAad(slot.type, parsed.params))
            cipher.doFinal(parsed.wrapped)
        } catch (_: javax.crypto.AEADBadTagException) {
            null
        } finally {
            kek.fill(0)
        }
    }

    private class ParsedSlot(
        val params: ByteArray,
        val wrapped: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val cost: Argon2Cost,
    )

    /**
     * Split a slot body into its parts, or return null when it is not a slot
     * this key could open — wrong kind, or a body whose length does not match
     * what that kind requires.
     */
    private fun parseSlot(
        slot: Slot,
        unlock: Unlock,
    ): ParsedSlot? {
        val expectedType = if (unlock is Unlock.Password) SLOT_PASSWORD else SLOT_RECOVERY
        val paramsLength = slot.body.size - WRAPPED_KEY_BYTES
        if (slot.type != expectedType || paramsLength <= 0) return null

        val params = slot.body.copyOfRange(0, paramsLength)
        val wrapped = slot.body.copyOfRange(paramsLength, slot.body.size)
        val salt = params.copyOfRange(0, SALT_BYTES)

        return when (unlock) {
            is Unlock.Password -> {
                if (params.size != PASSWORD_PARAMS_BYTES) {
                    null
                } else {
                    var at = SALT_BYTES
                    val memoryKib = readIntBe(params, at)
                    at += Int.SIZE_BYTES
                    val iterations = params[at++].toInt() and BYTE_MASK
                    val parallelism = params[at++].toInt() and BYTE_MASK
                    ParsedSlot(
                        params = params,
                        wrapped = wrapped,
                        salt = salt,
                        nonce = params.copyOfRange(at, at + GCM_NONCE_BYTES),
                        cost = Argon2Cost(memoryKib, iterations, parallelism),
                    )
                }
            }

            is Unlock.Recovery ->
                if (params.size != SALT_BYTES + GCM_NONCE_BYTES) {
                    null
                } else {
                    ParsedSlot(
                        params = params,
                        wrapped = wrapped,
                        salt = salt,
                        nonce = params.copyOfRange(SALT_BYTES, params.size),
                        cost = DEFAULT_COST,
                    )
                }
        }
    }

    private fun deriveKek(
        unlock: Unlock,
        salt: ByteArray,
        cost: Argon2Cost,
    ): ByteArray =
        when (unlock) {
            is Unlock.Password -> argon2id(unlock.password, salt, cost)
            // A 256-bit random key has nothing to stretch: HKDF is there to
            // bind it to this container's salt and give a clean 32-byte key,
            // not to make anything expensive.
            is Unlock.Recovery -> hkdf(unlock.key, salt)
        }

    private fun argon2id(
        password: String,
        salt: ByteArray,
        cost: Argon2Cost,
    ): ByteArray {
        val params =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(cost.memoryKib)
                .withIterations(cost.iterations)
                .withParallelism(cost.parallelism)
                .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        return ByteArray(KEY_BYTES).also { generator.generateBytes(password.toCharArray(), it) }
    }

    private fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, HKDF_INFO))
        return ByteArray(KEY_BYTES).also { generator.generateBytes(it, 0, it.size) }
    }

    // ── payload ──────────────────────────────────────────────────────────────

    private fun writeChunks(
        plaintext: InputStream,
        out: DataOutputStream,
        dataKey: ByteArray,
        noncePrefix: ByteArray,
    ) {
        val buffer = ByteArray(CHUNK_SIZE)
        var index = 0L
        var pending = readFully(plaintext, buffer)

        do {
            val next =
                if (pending == CHUNK_SIZE) {
                    val lookahead = ByteArray(CHUNK_SIZE)
                    val read = readFully(plaintext, lookahead)
                    if (read > 0) lookahead to read else null
                } else {
                    null
                }
            val isLast = next == null

            val cipher = Cipher.getInstance(CONTAINER_CIPHER)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(dataKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, chunkNonce(noncePrefix, index)),
            )
            cipher.updateAAD(chunkAad(index, isLast))
            val ciphertext = cipher.doFinal(buffer, 0, pending)
            out.writeInt(ciphertext.size)
            out.write(ciphertext)

            if (next != null) {
                next.first.copyInto(buffer)
                pending = next.second
                index++
            } else {
                pending = -1
            }
        } while (pending >= 0)
    }

    private const val MAX_SLOTS = 8
    private const val BYTE_MASK = 0xFF
    private const val PASSWORD_PARAMS_BYTES = SALT_BYTES + Int.SIZE_BYTES + 1 + 1 + GCM_NONCE_BYTES
    private const val MAX_SLOT_BODY = 4096
    private val HKDF_INFO = "zamolxis-container-v3-recovery".toByteArray()
}
