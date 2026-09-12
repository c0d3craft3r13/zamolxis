/*
 * How this build configures mlkem-native. Our file; everything under
 * vendor/mlkem-native/ is upstream's and is not edited.
 *
 * ## Deterministic API only
 *
 * MLK_CONFIG_NO_RANDOMIZED_API removes `keypair` and `enc` and leaves
 * `keypair_derand`, `enc_derand` and `dec`. Upstream is explicit that it
 * provides no random number generator and that the consumer must supply one —
 * so this supplies none, and every byte of randomness is passed in from Python,
 * which draws it from `os.urandom` like the rest of the protocol.
 *
 * That is not only convenience. A second entropy source inside the C would be a
 * second thing to get right, and the failure mode of getting it wrong is
 * predictable keys — the kind of bug that leaves no trace and breaks
 * everything.
 *
 * ## Portable C, not the AArch64 assembly
 *
 * The native backends are left off. ML-KEM-768 in mlkem-native's portable C
 * runs in well under a millisecond on a phone, and this protocol encapsulates
 * once per epoch — once per week, or per five hundred messages. There is no
 * speed here worth a second code path per architecture.
 *
 * The assembly is vendored anyway, so enabling it later is a change to this
 * file rather than another trip to upstream. What would motivate it is the
 * formal constant-time proof that covers the AArch64 backend, not throughput.
 *
 * Inline assembly generally stays on (MLK_CONFIG_NO_ASM is *not* set): that is
 * what upstream uses for value barriers, which is a constant-time measure
 * rather than an optimisation.
 */

#ifndef MAYAK_MLKEM_CONFIG_H
#define MAYAK_MLKEM_CONFIG_H

/* ML-KEM-768. The same parameter set the desktop build gets from cryptography,
 * because the two have to open each other's messages. */
#define MLK_CONFIG_PARAMETER_SET 768

/* Namespaced away from our own exports so a reader can tell at a glance which
 * symbols are upstream's and which are this project's. A single-level build
 * does not append the parameter set, so the symbols are mlk_upstream_enc_derand
 * and so on — not mlk_upstream768_, which is the multi-level spelling. */
#define MLK_CONFIG_NAMESPACE_PREFIX mlk_upstream

/* No randombytes() to provide, because no API that would call one. */
#define MLK_CONFIG_NO_RANDOMIZED_API

#endif /* MAYAK_MLKEM_CONFIG_H */
