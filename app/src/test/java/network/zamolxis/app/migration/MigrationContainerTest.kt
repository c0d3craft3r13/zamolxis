package network.zamolxis.app.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The v3 export container.
 *
 * Every test runs Argon2id at a deliberately trivial cost. The real
 * parameters — 128 MiB, three passes — take about a second and a whole lot of
 * memory each, which is the entire point of them and exactly what a test suite
 * cannot afford. The cost lives in the file, so exercising the format at one
 * cost proves it at any other.
 */
class MigrationContainerTest {
    private val cheap = MigrationContainer.Argon2Cost(memoryKib = 512, iterations = 1, parallelism = 1)
    private val password = MigrationContainer.Unlock.Password("correct horse battery staple")
    private val payload = "identities, messages, contacts".toByteArray()

    private fun seal(
        data: ByteArray = payload,
        unlocks: List<MigrationContainer.Unlock> = listOf(password),
    ) = MigrationContainer.seal(data, unlocks, cheap)

    @Test
    fun `a password round trips`() {
        val opened = MigrationContainer.open(seal(), password)

        assertArrayEquals(payload, opened)
    }

    @Test
    fun `the container announces itself as version 3`() {
        assertEquals(MigrationContainer.VERSION, seal()[0])
    }

    @Test
    fun `a recovery key round trips`() {
        val key = RecoveryKey.generate()
        val unlock = MigrationContainer.Unlock.Recovery(key)

        val opened = MigrationContainer.open(seal(unlocks = listOf(unlock)), unlock)

        assertArrayEquals(payload, opened)
    }

    /**
     * The reason the format has slots at all: a user who wrote the recovery
     * key down is not exposed to their own weak password, and a user who
     * remembers the password does not need the paper.
     */
    @Test
    fun `either slot opens the same container independently`() {
        val key = RecoveryKey.generate()
        val recovery = MigrationContainer.Unlock.Recovery(key)
        val container = seal(unlocks = listOf(password, recovery))

        assertArrayEquals(payload, MigrationContainer.open(container, password))
        assertArrayEquals(payload, MigrationContainer.open(container, recovery))
    }

    @Test
    fun `a wrong password is refused`() {
        val container = seal()

        assertThrows(WrongPasswordException::class.java) {
            MigrationContainer.open(container, MigrationContainer.Unlock.Password("nearly right"))
        }
    }

    @Test
    fun `a wrong recovery key is refused`() {
        val recovery = MigrationContainer.Unlock.Recovery(RecoveryKey.generate())
        val container = seal(unlocks = listOf(recovery))

        assertThrows(WrongPasswordException::class.java) {
            MigrationContainer.open(container, MigrationContainer.Unlock.Recovery(RecoveryKey.generate()))
        }
    }

    /**
     * A password must not open a container through a recovery slot, or the
     * expensive KDF could be skipped entirely.
     */
    @Test
    fun `a password cannot open a recovery-only container`() {
        val recovery = MigrationContainer.Unlock.Recovery(RecoveryKey.generate())
        val container = seal(unlocks = listOf(recovery))

        assertThrows(WrongPasswordException::class.java) {
            MigrationContainer.open(container, password)
        }
    }

    @Test
    fun `a flipped byte in the payload is caught`() {
        val container = seal()
        container[container.size - 1] = (container[container.size - 1].toInt() xor 0x01).toByte()

        assertThrows(InvalidExportFileException::class.java) {
            MigrationContainer.open(container, password)
        }
    }

    /**
     * Truncation is the attack a naive chunked format invites: cut the file
     * and the reader hands back a prefix as if it were the whole thing. The
     * last chunk authenticates that it is last, so a cut file has no valid
     * ending and is rejected outright.
     */
    @Test
    fun `a truncated container is rejected rather than silently shortened`() {
        val big = ByteArray(MigrationContainer.CHUNK_SIZE * 3) { (it % 251).toByte() }
        val container = seal(big)

        val cut = container.copyOfRange(0, container.size - MigrationContainer.CHUNK_SIZE)

        assertThrows(InvalidExportFileException::class.java) {
            MigrationContainer.open(cut, password)
        }
    }

    /**
     * The Argon2 cost is readable — it has to be, to derive the key — so it
     * is authenticated instead. Editing it down to something cheap must break
     * the slot rather than produce a container that is cheaper to attack.
     */
    @Test
    fun `weakening the stored Argon2 cost breaks the slot`() {
        val container = seal()

        // Slot body begins after [version][slotCount][type][length]; the cost
        // follows the 16-byte salt.
        val memoryAt = 1 + 1 + 1 + 2 + 16
        container[memoryAt] = 0
        container[memoryAt + 1] = 0
        container[memoryAt + 2] = 0
        container[memoryAt + 3] = 8

        assertThrows(WrongPasswordException::class.java) {
            MigrationContainer.open(container, password)
        }
    }

    @Test
    fun `a payload spanning several chunks round trips`() {
        val big = ByteArray(MigrationContainer.CHUNK_SIZE * 2 + 1234) { (it % 251).toByte() }

        assertArrayEquals(big, MigrationContainer.open(seal(big), password))
    }

    /** The boundary that broke the first draft: the final chunk is empty. */
    @Test
    fun `a payload that is an exact multiple of the chunk size round trips`() {
        val exact = ByteArray(MigrationContainer.CHUNK_SIZE * 2) { (it % 251).toByte() }

        assertArrayEquals(exact, MigrationContainer.open(seal(exact), password))
    }

    @Test
    fun `an empty payload round trips`() {
        assertArrayEquals(ByteArray(0), MigrationContainer.open(seal(ByteArray(0)), password))
    }

    /**
     * Same input, same password, different bytes out — salt and nonces are
     * fresh per container. Two exports that looked alike would tell an
     * observer the data had not changed between them.
     */
    @Test
    fun `two containers of the same data differ`() {
        assertNotEquals(seal().toList(), seal().toList())
    }

    @Test
    fun `a recovery key survives being written down and typed back`() {
        val key = RecoveryKey.generate(SecureRandom())

        val text = RecoveryKey.encode(key)

        assertArrayEquals(key, RecoveryKey.decode(text))
    }

    /** People transcribe from paper. Case and the grouping dashes must not matter. */
    @Test
    fun `recovery key parsing forgives case grouping and spaces`() {
        val key = RecoveryKey.generate()
        val text = RecoveryKey.encode(key)

        val mangled = text.lowercase().replace("-", " ")

        assertArrayEquals(key, RecoveryKey.decode(mangled))
    }

    @Test
    fun `a recovery key contains no characters that look like each other`() {
        val text = RecoveryKey.encode(RecoveryKey.generate())

        assertTrue("saw a confusable character in $text", text.none { it in "ILOU" })
    }

    @Test
    fun `a version 2 file is not mistaken for a version 3 container`() {
        val legacy = MigrationCrypto.encrypt(payload, "legacy")

        assertThrows(InvalidExportFileException::class.java) {
            MigrationContainer.open(legacy, password)
        }
    }

    @Test
    fun `a container with no slots is refused at creation`() {
        assertThrows(IllegalArgumentException::class.java) {
            MigrationContainer.seal(payload, emptyList(), cheap)
        }
    }

    // ── how the app actually uses it ─────────────────────────────────────────

    /**
     * The shape the exporter relies on: a ZipOutputStream writing straight
     * through the container into the file, so no plaintext archive is ever
     * produced. If this breaks, the export silently starts leaving one behind.
     */
    @Test
    fun `an archive written through the sealing stream reads back whole`() {
        val sink = java.io.ByteArrayOutputStream()
        MigrationContainer.sealingStream(sink, listOf(password), cheap).use { sealed ->
            java.util.zip.ZipOutputStream(sealed).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write("""{"version":9}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("attachments/big.bin"))
                zip.write(ByteArray(MigrationContainer.CHUNK_SIZE * 2 + 7) { (it % 251).toByte() })
                zip.closeEntry()
            }
        }

        val entries = mutableMapOf<String, Int>()
        val opened = MigrationContainer.open(sink.toByteArray().inputStream(), password)
        java.util.zip.ZipInputStream(opened.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().size
                entry = zip.nextEntry
            }
        }

        assertEquals(setOf("manifest.json", "attachments/big.bin"), entries.keys)
        assertEquals(MigrationContainer.CHUNK_SIZE * 2 + 7, entries["attachments/big.bin"])
    }

    @Test
    fun `decryptAny opens a v3 container`() {
        assertArrayEquals(payload, MigrationCrypto.decryptAny(seal(), password))
    }

    /** Backups people already hold were written by v2 and must keep opening. */
    @Test
    fun `decryptAny still opens a v2 file`() {
        val legacy = MigrationCrypto.encrypt(payload, "legacy")

        assertArrayEquals(payload, MigrationCrypto.decryptAny(legacy, MigrationContainer.Unlock.Password("legacy")))
    }

    @Test
    fun `decryptAny says plainly that a v2 file has no recovery slot`() {
        val legacy = MigrationCrypto.encrypt(payload, "legacy")

        assertThrows(WrongPasswordException::class.java) {
            MigrationCrypto.decryptAny(legacy, MigrationContainer.Unlock.Recovery(RecoveryKey.generate()))
        }
    }

    @Test
    fun `isEncrypted recognises a v3 container`() {
        assertTrue(MigrationCrypto.isEncrypted(seal()))
    }

    // ── one field for either secret ──────────────────────────────────────────

    @Test
    fun `a typed password is offered only as a password`() {
        val unlocks = MigrationCrypto.unlocksFor("correct horse battery staple")

        assertEquals(1, unlocks.size)
        assertTrue(unlocks.single() is MigrationContainer.Unlock.Password)
    }

    /** Cheap first: HKDF before Argon2id, so a recovery key costs nothing to rule out. */
    @Test
    fun `a recovery key is recognised and tried first`() {
        val text = RecoveryKey.encode(RecoveryKey.generate())

        val unlocks = MigrationCrypto.unlocksFor(text)

        assertEquals(2, unlocks.size)
        assertTrue(unlocks.first() is MigrationContainer.Unlock.Recovery)
    }

    @Test
    fun `one field opens a container sealed with a password`() {
        val container = seal()

        assertArrayEquals(payload, MigrationCrypto.decryptWithSecret(container, "correct horse battery staple"))
    }

    @Test
    fun `one field opens a container sealed with a recovery key`() {
        val key = RecoveryKey.generate()
        val container = seal(unlocks = listOf(MigrationContainer.Unlock.Recovery(key)))

        assertArrayEquals(payload, MigrationCrypto.decryptWithSecret(container, RecoveryKey.encode(key)))
    }

    @Test
    fun `one field reports the same failure for either kind of wrong secret`() {
        val container = seal()

        assertThrows(WrongPasswordException::class.java) {
            MigrationCrypto.decryptWithSecret(container, "not the password")
        }
        assertThrows(WrongPasswordException::class.java) {
            MigrationCrypto.decryptWithSecret(container, RecoveryKey.encode(RecoveryKey.generate()))
        }
    }
}
