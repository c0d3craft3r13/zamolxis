package network.zamolxis.app.rns.backend.py

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.PyObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * ML-KEM-768 from our own C, on the phone, against answers another implementation gave.
 *
 * `libmayak_mlkem.so` is the vendored mlkem-native behind `mayak_mlkem.c`, and on
 * Android it is the post-quantum half of the Mayak protocol because Chaquopy's
 * `cryptography` stops at 42.0.8. The other end of a conversation may be a laptop
 * running `cryptography` 47+, which is AWS-LC underneath — a second, independent
 * implementation of FIPS 203. This checks they are the same ML-KEM, on the device
 * that ships it.
 *
 * Every expected value below was produced or verified by `cryptography` on the
 * desktop, never copied from a run of this test:
 *
 * - the public key hash is what `cryptography` derives from [SEED];
 * - [CT_FROM_LAPTOP] and [SS_FROM_LAPTOP] are an encapsulation `cryptography` made;
 * - [CT_NATIVE_SHA256] and [SS_NATIVE] are what this C produces from [COINS] when
 *   compiled for x86-64 Windows, and `cryptography` decapsulated that ciphertext to
 *   that secret before they were written down.
 *
 * So the last case is also a check across architectures: arm64 has to produce the
 * same bytes as x86-64 from the same inputs.
 *
 * Driven through Python's ctypes, not JNI, because that is how the protocol calls it.
 */
@RunWith(AndroidJUnit4::class)
class MlKemNativeInstrumentedTest {
    private val python by lazy {
        PythonRnsRuntime(ApplicationProvider.getApplicationContext()).python
    }

    private val ctypes by lazy { python.getModule("ctypes") }

    private val library by lazy { ctypes.callAttr("CDLL", "libmayak_mlkem.so") }

    private fun call(name: String, vararg arguments: Any): Int = library.callAttr(name, *arguments).toInt()

    private fun buffer(size: Int): PyObject = ctypes.callAttr("create_string_buffer", size)

    private fun PyObject.bytes(): ByteArray = checkNotNull(this["raw"]).toJava(ByteArray::class.java)

    @Test
    fun itIsTheBuildThisBindingExpects() {
        assertEquals(1, call("mayak_mlkem_abi_version"))
        assertEquals(64, call("mayak_mlkem_seed_bytes"))
        assertEquals(32, call("mayak_mlkem_coins_bytes"))
        assertEquals(1184, call("mayak_mlkem_public_key_bytes"))
        assertEquals(1088, call("mayak_mlkem_ciphertext_bytes"))
        assertEquals(32, call("mayak_mlkem_shared_secret_bytes"))
    }

    @Test
    fun thePhoneDerivesTheSamePublicKeyTheLaptopDoes() {
        assertEquals(PK_SHA256, sha256(publicKey()))
    }

    @Test
    fun thePhoneOpensWhatTheLaptopSealed() {
        val secret = buffer(32)

        assertEquals(0, call("mayak_mlkem_decapsulate", secret, CT_FROM_LAPTOP.hex().toPyBytes(), SEED.hex().toPyBytes()))

        assertArrayEquals(SS_FROM_LAPTOP.hex(), secret.bytes())
    }

    @Test
    fun thePhoneSealsExactlyWhatTheLaptopVerified() {
        val ciphertext = buffer(1088)
        val secret = buffer(32)

        assertEquals(0, call("mayak_mlkem_encapsulate", ciphertext, secret, publicKey().toPyBytes(), COINS.hex().toPyBytes()))

        assertEquals(CT_NATIVE_SHA256, sha256(ciphertext.bytes()))
        assertArrayEquals(SS_NATIVE.hex(), secret.bytes())
    }

    @Test
    fun theWrongSeedGetsADifferentSecretRatherThanAnError() {
        val wrongSeed = SEED.hex().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val secret = buffer(32)

        assertEquals(0, call("mayak_mlkem_decapsulate", secret, CT_FROM_LAPTOP.hex().toPyBytes(), wrongSeed.toPyBytes()))

        assertNotEquals(SS_FROM_LAPTOP, secret.bytes().toHex())
    }

    @Test
    fun aPublicKeyThatFailsTheModulusCheckIsRefused() {
        val badKey = ByteArray(1184) { 0xFF.toByte() }

        assertNotEquals(0, call("mayak_mlkem_encapsulate", buffer(1088), buffer(32), badKey.toPyBytes(), COINS.hex().toPyBytes()))
    }

    private fun publicKey(): ByteArray {
        val key = buffer(1184)
        assertEquals(0, call("mayak_mlkem_public_key_from_seed", key, SEED.hex().toPyBytes()))
        return key.bytes()
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val SEED =
            "637edaf1acf64e1313aedba8954ae0695e765483fbee11009b061436e3d27e57" +
                "65ca9d4cb0226f3d1a0275e96f33d2619f2aad00c99e7e01bedfcd51e8014394"

        const val COINS = "7bffd683c98daf5bc4ed813fafbe13e554c7cfda4f961edbda41c1df7444d67f"

        const val PK_SHA256 = "bbc4ab371dd072a1bc27f59c6aed3ccd0c96d6bb5fc9617be2554286d0f5d0c6"

        const val CT_FROM_LAPTOP =
                "66215d12b10318f9f8ac92eaf580bab4c16d4b530c48e8348a320d04632c3daac06c9ac8f35a7b1e06008b238e76ead5" +
                "7dd4a0d9506b3363814610ddde4c389573388fd2b66dc9731c8edce4099650e11a083feb2dec3513fe892e5beca25a4b" +
                "03b2c3e5a25c5a0eacd8c939d1aca90bdacd877cbc3c329868ec845d904fd31f8e163ebd6a31235a426441e3d140d691" +
                "10404482758f17fc838d01fc38dc07d29dee297415733d561687467d7d885cc013d1c7a16e5d3f6c6ba3c59563ba301b" +
                "f0166c603449f5d13b94ded5cfd86ae5a8e3fef70d162eeec735440d2934cbb7fe74c87a700fbb798f9f5c6c088990dd" +
                "4ed0b9b05452ebe6106ed64abda01b51c01bfd5f59eeb76815c639bcc4feb13962306e73135b8abd44416eda55809241" +
                "8b5ebf5ab26079bfade215d87200d06796fc9e8bdf0d8732aef38b08cba0c42a042ba190887e9d217456b3a8ba591ce4" +
                "7bf9efea8bc2e6a5ead29d251cbe446b78493b52c14097ba3af80391b086a8537dbf85d6f53c465df19fc23708dbce89" +
                "28f8de3abd5e98712db386b54d2fc3535b627ab103045ecb66c9eb4587a07bb02db628a7b0aad55ab580bd5b86150a88" +
                "cb36df46e66733f4ec942d3d98abbfc98809cba8816c0343ffb36874acc5e37d985282cbb08908d636d702e1fe256eb6" +
                "e2a3a855520ecb086d9b0ea8b53edea0daa687ec88b5a8c8ae0d262147b3c2474131a45ea780c9a7702198c5ddb2c283" +
                "1a66b0a91beb0f05afbc3e7b69a586cdb02d7945f0a8ee3d516145666e9996cf7777847031d3776f2bc95d8d8ab95766" +
                "ce2ab80c30e1aa4e88c70138993f6dec620be46a0a9c17e3986244d064bac2f8a76d515a7e3307fd8fad23e3d3595500" +
                "5d2623dfcfd8d916cc1d0f7d0d6e7e644979acf2ace599a2075384b8db91565173376e1e1c23da6e8ca6bcf3f18a8197" +
                "462dc22d08e2a7d6a0083bf1d0ce8b43a68e2439e378339e473db378515bc8c5c90c8d9d2b4c6fb0b992e7e8df026f3a" +
                "aec155175c94fed50675cb1c691de38d41a75ee676c1b74bb849779960f95b2032a3bd46eb1300bdc0ac7931b0a4bf20" +
                "062d3003eeec33247b7c7eb368afb1f3cc485100893a591910e65c6551ead4da7b686be2de0de572eddd42b0920a9d27" +
                "6ac8103b8342362f5db25a12636581d93eb6ee3a63ad8d92695d465c06f9ef4086a0a2ab1b4af2b46a7f28db20c63e22" +
                "3ea05db381d044418c273410caa32f18d55fcb6ea2a2bb3d3e01bd3731879a3eea007f336de5d25b5593190a12eded69" +
                "b6f761fe2108af46266a93bc82886c9cf6158bfccf67306a7f393c6d25493fba486b6843fd50fa49e96eaac20d49a443" +
                "13d5579156bf26f900349aae361a0160f1bc7d0e807d0bd6784e1243203598b6427b7acc745ad4d272c47dec207719cd" +
                "54671d183279d83ae747ac260139947411673c6cbb6e2e63670b6417e5d2e2c5cfc26d1947e74c727088d688b930cb71" +
                "c065a3d0ec168c23b452c65f2788ac91dc15d8979201cffb75bc0c37cfbdc378"

        const val SS_FROM_LAPTOP = "b20a3e8927d0538899e21760589609db48f2ab3d99362cafda442b20354916ec"

        const val CT_NATIVE_SHA256 = "d74b9a6d6a678372bf8a3af7dbdc6871c9b29eaf4b8cf35c55ee66180d6aef92"

        const val SS_NATIVE = "2c99c45bca26ba5e73185478ef1b759b293da26f44637f7d024ed6b26ace6134"
    }
}
