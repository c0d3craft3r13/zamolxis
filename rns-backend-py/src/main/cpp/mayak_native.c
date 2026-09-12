/*
 * Proof that Python inside this app can call C we shipped ourselves.
 *
 * ## Why this file exists
 *
 * The post-quantum half of the Mayak protocol is ML-KEM-768, and on the desktop
 * it comes from `cryptography` 47+, whose Rust binding is AWS-LC underneath. On
 * Android it cannot: Chaquopy's package index stops at cryptography 42.0.8, and
 * ML-KEM arrived in 47.
 *
 * The route that keeps every line of protocol logic in Python is to ship the
 * FIPS 203 implementation as a native library and bind it with `ctypes` — the
 * same arrangement as the desktop, only with our own `.so` instead of the one
 * inside a wheel. That rests on one assumption: that Chaquopy's CPython can
 * dlopen a library this project built and call into it.
 *
 * `_ctypes.cpython-311.so` is in Chaquopy's bootstrap-native set for all three
 * ABIs, which proves the module is there and proves nothing about our library.
 * So this is the smallest thing that answers the actual question, and it is
 * deliberately not cryptography: if the answer is no, there is no point
 * vendoring anybody's C.
 *
 * ## What it has to demonstrate
 *
 * Exactly the three shapes a KEM binding needs and nothing more: a call that
 * returns a value, a call that fills a caller-owned output buffer, and a call
 * that reads a caller-owned input buffer. Key generation, encapsulation and
 * decapsulation are all one or more of those.
 *
 * Parameter types are `int` rather than `size_t` on purpose. ctypes passes a
 * Python integer as a C `int` unless told otherwise, and on arm64 an argument
 * declared `size_t` would be read from a 64-bit register the caller only filled
 * 32 bits of. Declaring what ctypes actually sends is cheaper than remembering
 * to set `argtypes` at every call site.
 */

#include <stdint.h>

/*
 * Bumped when the shape of anything below changes. The Python side checks it
 * before calling anything else: a stale .so left in an APK by an incremental
 * build would otherwise be found by crashing somewhere less obvious.
 */
#define MAYAK_NATIVE_ABI 1

int mayak_native_abi_version(void) {
    return MAYAK_NATIVE_ABI;
}

/* Fills `out` with a sequence the caller can predict without sharing state. */
void mayak_native_fill(uint8_t *out, int length) {
    for (int i = 0; i < length; i++) {
        out[i] = (uint8_t)(i * 7 + 3);
    }
}

/*
 * Reads `data` and returns something that depends on every byte of it. Masked
 * to sixteen bits so the result cannot come back as a negative Python integer
 * through ctypes' default `int` return type, which would be a confusing way to
 * learn that the default exists.
 */
int mayak_native_checksum(const uint8_t *data, int length) {
    uint32_t sum = 0;
    for (int i = 0; i < length; i++) {
        sum = (sum * 31u) + data[i];
    }
    return (int)(sum & 0xFFFFu);
}
