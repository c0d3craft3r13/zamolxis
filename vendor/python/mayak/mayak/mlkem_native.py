"""ML-KEM-768 from our own C, for where `cryptography` cannot provide it.

## Where this is used

On Android. Chaquopy's newest `cryptography` is 42.0.8 and ML-KEM arrived in 47,
so the phone loads `libmayak_mlkem.so` — the vendored mlkem-native C behind a
thin wrapper — and this binds it with ctypes. Every line of protocol logic stays
in Python; only FIPS 203 itself is C, exactly as on the desktop, where it is C
inside `cryptography`.

On the desktop this is built by ``tools/build_mlkem_native.py`` and exists for
one reason: so the unit suite can check, on every run, that the phone's
implementation and `cryptography`'s agree in both directions. Two independent
implementations of one standard either interoperate or leave a conversation that
never opens, and the second is not the place to find out.

## Interchangeable with :class:`mayak.mlkem.MlKem768`, not merely similar

Same name, same sizes, and the same private key: the 64-byte seed, which is what
`cryptography`'s ``private_bytes_raw()`` returns. A device file written on either
opens on the other.

## Randomness comes from here

The C is built with only the deterministic API — no random number generator
inside it at all. Every random byte is drawn from :func:`os.urandom` in this
file, the source the rest of the protocol already uses. A second entropy source
in C would be a second thing to get right, and getting it wrong produces
predictable keys with nothing anywhere to show for it.

## The ABI check

The library reports a version before anything else is called. A stale ``.so``
left in an APK by an incremental build would otherwise disagree silently, and a
KEM that disagrees silently looks exactly like an attacker.
"""

from __future__ import annotations

import ctypes
import os
import sys
from pathlib import Path

from mayak.kem import Encapsulation, KemError

#: The wrapper's shape this binding was written against. See mayak_mlkem.c.
EXPECTED_ABI = 1

#: Set to a path to load a specific build — a test, a packaging check.
LIBRARY_ENVIRONMENT_VARIABLE = "MAYAK_MLKEM_LIBRARY"


class NativeLibraryMissing(ImportError):
    """No usable ML-KEM library on this machine. An ImportError, deliberately:
    callers that probe for a mechanism already treat that as "not here"."""


def _candidates() -> list[str]:
    explicit = os.environ.get(LIBRARY_ENVIRONMENT_VARIABLE)
    if explicit:
        # Asked for by name. Falling back to something else would test, or run,
        # a library nobody chose.
        return [explicit]

    if sys.platform == "win32":
        built = "mayak_mlkem.dll"
    elif sys.platform == "darwin":
        built = "libmayak_mlkem.dylib"
    else:
        built = "libmayak_mlkem.so"

    # By soname first: on Android the linker finds it in the APK's lib/<abi>/.
    # Then the desktop build output beside this checkout.
    return [built, str(Path(__file__).resolve().parent.parent / "build" / "native" / built)]


def _load() -> ctypes.CDLL:
    failures = []
    for candidate in _candidates():
        try:
            return ctypes.CDLL(candidate)
        except OSError as unavailable:
            failures.append(f"{candidate}: {unavailable}")
    raise NativeLibraryMissing("no ML-KEM native library could be loaded — " + "; ".join(failures))


def _bind(library: ctypes.CDLL) -> ctypes.CDLL:
    """Declare every signature, so nothing relies on ctypes' default of int.

    The wrapper takes no ``size_t`` precisely so defaults would be harmless, but
    a declared signature also refuses a wrong argument type at the call rather
    than handing C a pointer to the wrong thing.
    """
    buffer = ctypes.c_char_p
    for name in (
        "mayak_mlkem_abi_version",
        "mayak_mlkem_seed_bytes",
        "mayak_mlkem_coins_bytes",
        "mayak_mlkem_public_key_bytes",
        "mayak_mlkem_ciphertext_bytes",
        "mayak_mlkem_shared_secret_bytes",
    ):
        getattr(library, name).argtypes = []
        getattr(library, name).restype = ctypes.c_int

    library.mayak_mlkem_public_key_from_seed.argtypes = [ctypes.c_void_p, buffer]
    library.mayak_mlkem_public_key_from_seed.restype = ctypes.c_int
    library.mayak_mlkem_encapsulate.argtypes = [ctypes.c_void_p, ctypes.c_void_p, buffer, buffer]
    library.mayak_mlkem_encapsulate.restype = ctypes.c_int
    library.mayak_mlkem_decapsulate.argtypes = [ctypes.c_void_p, buffer, buffer]
    library.mayak_mlkem_decapsulate.restype = ctypes.c_int
    return library


class MlKem768Native:
    """ML-KEM-768 as a :class:`mayak.kem.Kem`, backed by our own C."""

    name = "ml-kem-768"
    post_quantum = True

    def __init__(self, library: ctypes.CDLL | None = None) -> None:
        self._library = _bind(library or _load())

        abi = self._library.mayak_mlkem_abi_version()
        if abi != EXPECTED_ABI:
            raise NativeLibraryMissing(f"the ML-KEM library speaks ABI {abi}; this binding expects {EXPECTED_ABI}")

        # Taken from the library rather than written down, so a build for some
        # other parameter set is refused here rather than read out of bounds.
        self.private_key_length = self._library.mayak_mlkem_seed_bytes()
        self.public_key_length = self._library.mayak_mlkem_public_key_bytes()
        self.ciphertext_length = self._library.mayak_mlkem_ciphertext_bytes()
        self.shared_secret_length = self._library.mayak_mlkem_shared_secret_bytes()
        self._coins_length = self._library.mayak_mlkem_coins_bytes()

        # FIPS 203's numbers. A library that reports others is not ML-KEM-768,
        # whatever its name, and must not be used as one.
        if (self.private_key_length, self.public_key_length, self.ciphertext_length, self.shared_secret_length) != (
            64,
            1184,
            1088,
            32,
        ):
            raise NativeLibraryMissing("the ML-KEM library reports sizes that are not ML-KEM-768's")

    def generate(self) -> tuple[bytes, bytes]:
        seed = os.urandom(self.private_key_length)
        return seed, self.public_key_from_seed(seed)

    def public_key_from_seed(self, seed: bytes) -> bytes:
        self._require(seed, self.private_key_length, "private key")
        public_key = ctypes.create_string_buffer(self.public_key_length)
        if self._library.mayak_mlkem_public_key_from_seed(public_key, seed) != 0:
            raise KemError(f"the {self.name} library could not derive a public key")
        return public_key.raw

    def encapsulate(self, public_key: bytes) -> Encapsulation:
        return self.encapsulate_with(public_key, os.urandom(self._coins_length))

    def encapsulate_with(self, public_key: bytes, coins: bytes) -> Encapsulation:
        """Encapsulation with the randomness supplied — for known-answer tests.

        Never call this with anything but fresh random coins outside a test:
        reused coins against one key reveal the shared secret.
        """
        self._require(public_key, self.public_key_length, "public key")
        self._require(coins, self._coins_length, "encapsulation coins")
        ciphertext = ctypes.create_string_buffer(self.ciphertext_length)
        shared_secret = ctypes.create_string_buffer(self.shared_secret_length)
        # Non-zero is upstream's FIPS 203 modulus check refusing the key.
        if self._library.mayak_mlkem_encapsulate(ciphertext, shared_secret, public_key, coins) != 0:
            raise KemError(f"the public key is not a usable {self.name} key")
        return Encapsulation(shared_secret=shared_secret.raw, ciphertext=ciphertext.raw)

    def decapsulate(self, private_key: bytes, ciphertext: bytes) -> bytes:
        self._require(private_key, self.private_key_length, "private key")
        self._require(ciphertext, self.ciphertext_length, "ciphertext")
        shared_secret = ctypes.create_string_buffer(self.shared_secret_length)
        if self._library.mayak_mlkem_decapsulate(shared_secret, ciphertext, private_key) != 0:
            raise KemError(f"the key material is not a usable {self.name} pair")
        return shared_secret.raw

    def _require(self, value: bytes, length: int, what: str) -> None:
        if not isinstance(value, bytes) or len(value) != length:
            size = len(value) if isinstance(value, bytes | bytearray) else type(value).__name__
            raise KemError(f"an {self.name} {what} is {length} bytes, not {size}")
