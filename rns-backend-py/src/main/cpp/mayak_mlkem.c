/*
 * ML-KEM-768 for the Mayak protocol, shaped for a ctypes caller.
 *
 * ## Why there is a wrapper at all
 *
 * Upstream's API is the FIPS 203 one, and it is not quite what this protocol
 * stores. Two differences, both deliberate on our side:
 *
 * **The private key is the 64-byte seed, not the 2400-byte expanded key.**
 * `cryptography` on the desktop does the same — its `private_bytes_raw()` is
 * the seed and `from_seed_bytes()` reconstructs from it. The two builds have to
 * be interchangeable, because a device file written on one must open on the
 * other, and because one end of a conversation may be a phone and the other a
 * laptop. Sixty-four bytes is also simply less to keep track of, and what is
 * kept is what has to be destroyed.
 *
 * So decapsulation re-derives the expanded key from the seed each time. That
 * costs one key generation per decapsulation, which at once per epoch is
 * nothing worth trading correctness for.
 *
 * **Randomness comes from the caller.** See mayak_mlkem_config.h: only the
 * deterministic API is compiled, so every random byte arrives from Python's
 * `os.urandom`. Nothing here invents entropy.
 *
 * ## The ctypes contract
 *
 * Plain C, no structs across the boundary, lengths as `int` rather than
 * `size_t` — ctypes sends a Python integer as a C `int` unless told otherwise,
 * and a `size_t` parameter on arm64 would be read from a register the caller
 * only half filled.
 *
 * Buffers are caller-owned and their sizes are fixed by the parameter set. The
 * Python side asks for those sizes through the accessors below rather than
 * writing them down twice, so a mismatched build is caught at import instead of
 * as a wrong answer.
 *
 * Every function returns 0 on success and non-zero on failure, and on failure
 * the output buffers are left zeroed rather than half written.
 */

#include <stdint.h>
#include <string.h>

#include "mlkem_native.h"

/*
 * Bumped whenever anything below changes shape. The Python side checks it
 * before it calls anything else: an incremental build that left a stale .so in
 * the APK would otherwise be discovered as a decapsulation that silently
 * disagrees, which is the worst possible place to find out.
 */
#define MAYAK_MLKEM_ABI 1

/* d || z, FIPS 203's key generation input, and what we store as the private key. */
#define MAYAK_MLKEM_SEED_BYTES (2 * MLKEM_SYMBYTES)

/* Encaps randomness, FIPS 203 Algorithm 17. */
#define MAYAK_MLKEM_COINS_BYTES MLKEM_SYMBYTES

/*
 * memset through a volatile pointer. The expanded secret key is rebuilt on the
 * stack for every decapsulation, and a plain memset on a buffer that is never
 * read again is exactly what a compiler is entitled to delete.
 *
 * This is the same measure upstream applies to its own intermediates, repeated
 * here because that one is internal and this buffer is ours.
 */
static void mayak_wipe(void *buffer, size_t length)
{
  volatile uint8_t *target = (volatile uint8_t *)buffer;
  while (length--)
  {
    *target++ = 0;
  }
}

int mayak_mlkem_abi_version(void) { return MAYAK_MLKEM_ABI; }

int mayak_mlkem_seed_bytes(void) { return MAYAK_MLKEM_SEED_BYTES; }

int mayak_mlkem_coins_bytes(void) { return MAYAK_MLKEM_COINS_BYTES; }

int mayak_mlkem_public_key_bytes(void) { return MLKEM768_PUBLICKEYBYTES; }

int mayak_mlkem_ciphertext_bytes(void) { return MLKEM768_CIPHERTEXTBYTES; }

int mayak_mlkem_shared_secret_bytes(void) { return MLKEM_BYTES; }

/*
 * Public key for a seed. There is no "generate" here on purpose: the seed is
 * the private key, the caller drew it, and this only says what it implies.
 */
int mayak_mlkem_public_key_from_seed(uint8_t *public_key, const uint8_t *seed)
{
  uint8_t expanded[MLKEM768_SECRETKEYBYTES];
  int failed;

  failed = mlk_upstream_keypair_derand(public_key, expanded, seed);
  mayak_wipe(expanded, sizeof(expanded));
  if (failed)
  {
    mayak_wipe(public_key, MLKEM768_PUBLICKEYBYTES);
  }
  return failed;
}

int mayak_mlkem_encapsulate(uint8_t *ciphertext, uint8_t *shared_secret,
                            const uint8_t *public_key, const uint8_t *coins)
{
  return mlk_upstream_enc_derand(ciphertext, shared_secret, public_key, coins);
}

/*
 * Decapsulation from the stored seed: rebuild the expanded key, use it, wipe it.
 *
 * With the wrong seed this returns success and a different shared secret. That
 * is FIPS 203's implicit rejection and not a defect to paper over — reporting
 * failure here would tell an attacker which of their guesses was closer. What
 * catches a wrong key is the seal failing one layer up.
 */
int mayak_mlkem_decapsulate(uint8_t *shared_secret, const uint8_t *ciphertext,
                            const uint8_t *seed)
{
  uint8_t expanded[MLKEM768_SECRETKEYBYTES];
  uint8_t unused_public_key[MLKEM768_PUBLICKEYBYTES];
  int failed;

  failed = mlk_upstream_keypair_derand(unused_public_key, expanded, seed);
  if (!failed)
  {
    failed = mlk_upstream_dec(shared_secret, ciphertext, expanded);
  }

  mayak_wipe(expanded, sizeof(expanded));
  mayak_wipe(unused_public_key, sizeof(unused_public_key));
  if (failed)
  {
    mayak_wipe(shared_secret, MLKEM_BYTES);
  }
  return failed;
}
