"""The Android Keystore as a :class:`mayak.vault.KeyVault`, reached from Python.

## No Kotlin

Chaquopy's ``java`` module calls platform classes directly, so this is Python
talking to ``android.security.keystore`` with nothing compiled in between. It
imports only inside an Android app; anywhere else ``from java import jclass``
fails and :func:`mayak.vault.hardware_vault` reports no vault.

## What the keys are

AES-256-GCM keys generated inside the Keystore and never exportable. Each wraps
exactly one store file key. StrongBox — a separate secure chip — is asked for
first and the trusted execution environment accepted when a phone has none.
A key that lands in software is destroyed at once and the vault refused, because
a file bound to it would claim a protection it does not have.

## Deliberately not required: an unlocked screen

``setUnlockedDeviceRequired`` would make these keys unusable while the phone is
locked. Mayak saves before it shows each message, so that would stop a locked
phone from receiving anything. Left off; the keys are usable after the first
unlock, which is the Android default and is said here rather than implied.

## How far destruction goes

``KeyStore.deleteEntry`` removes the key from the Keystore. How completely the
key blob is erased is the device's KeyMint implementation's business; on
hardware with rollback resistance it is enforced by the secure element. It is a
far stronger claim than overwriting a file, and not an absolute one.
"""

from __future__ import annotations

from java import jclass  # noqa: F401 - the import that makes this Android-only

from mayak.vault import VaultError, VaultUnavailable

_KEYSTORE = "AndroidKeyStore"
_TRANSFORMATION = "AES/GCM/NoPadding"
_IV_LENGTH = 12
_TAG_BITS = 128


class AndroidKeystoreVault:
    """Keys held by the Android Keystore, StrongBox where the phone has one."""

    def __init__(self) -> None:
        self._KeyGenerator = jclass("javax.crypto.KeyGenerator")
        self._KeyProperties = jclass("android.security.keystore.KeyProperties")
        self._Builder = jclass("android.security.keystore.KeyGenParameterSpec$Builder")
        self._KeyStore = jclass("java.security.KeyStore")
        self._Cipher = jclass("javax.crypto.Cipher")
        self._GCMParameterSpec = jclass("javax.crypto.spec.GCMParameterSpec")

        self._store = self._KeyStore.getInstance(_KEYSTORE)
        self._store.load(None)
        self.name = self._probe()

    # ----------------------------------------------------------------- vault

    def create(self, alias: str) -> None:
        if self._strongbox:
            try:
                self._generate(alias, strongbox=True)
                return
            except Exception:  # noqa: BLE001 - StrongBox refusing arrives as several Java types
                self.destroy(alias)
        self._generate(alias, strongbox=False)

    def wrap(self, alias: str, secret: bytes) -> bytes:
        cipher = self._Cipher.getInstance(_TRANSFORMATION)
        try:
            cipher.init(self._Cipher.ENCRYPT_MODE, self._key(alias))
            iv = bytes(cipher.getIV())
            return iv + bytes(cipher.doFinal(secret))
        except VaultError:
            raise
        except Exception as failure:  # noqa: BLE001 - Java exceptions, one outcome
            raise VaultError(f"the keystore could not wrap under {alias}: {failure}") from failure

    def unwrap(self, alias: str, wrapped: bytes) -> bytes:
        if len(wrapped) <= _IV_LENGTH:
            raise VaultError("the wrapped secret is too short")
        cipher = self._Cipher.getInstance(_TRANSFORMATION)
        try:
            spec = self._GCMParameterSpec(_TAG_BITS, wrapped[:_IV_LENGTH])
            cipher.init(self._Cipher.DECRYPT_MODE, self._key(alias), spec)
            return bytes(cipher.doFinal(wrapped[_IV_LENGTH:]))
        except VaultError:
            raise
        except Exception as failure:  # noqa: BLE001 - Java exceptions, one outcome
            raise VaultError(f"the keystore could not unwrap under {alias}") from failure

    def destroy(self, alias: str) -> None:
        try:
            if self._store.containsAlias(alias):
                self._store.deleteEntry(alias)
        except Exception as failure:  # noqa: BLE001 - Java exceptions, one outcome
            raise VaultError(f"the keystore could not destroy {alias}: {failure}") from failure

    def aliases(self, prefix: str) -> list[str]:
        # Through Collections.list and explicit size()/get(): Chaquopy sees
        # KeyStore.aliases() as the abstract Enumeration interface and refuses to
        # call nextElement on it — found on the phone — and this module's own
        # rules record that a Java ArrayList is not iterable from Python.
        listed = jclass("java.util.Collections").list(self._store.aliases())
        names = [str(listed.get(index)) for index in range(listed.size())]
        return [alias for alias in names if alias.startswith(prefix)]

    # -------------------------------------------------------------- internal

    def _key(self, alias: str):
        key = self._store.getKey(alias, None)
        if key is None:
            raise VaultError(f"no key named {alias}")
        return key

    def _generate(self, alias: str, *, strongbox: bool) -> None:
        properties = self._KeyProperties
        builder = self._Builder(alias, properties.PURPOSE_ENCRYPT | properties.PURPOSE_DECRYPT)
        builder.setBlockModes(properties.BLOCK_MODE_GCM)
        builder.setEncryptionPaddings(properties.ENCRYPTION_PADDING_NONE)
        builder.setKeySize(256)
        builder.setRandomizedEncryptionRequired(True)
        if strongbox:
            builder.setIsStrongBoxBacked(True)
        generator = self._KeyGenerator.getInstance(properties.KEY_ALGORITHM_AES, _KEYSTORE)
        generator.init(builder.build())
        generator.generateKey()

    def _probe(self) -> str:
        """Find out what this phone's Keystore really is, with a throwaway key."""
        alias = "mayak-vault-probe"
        self._strongbox = True
        try:
            self.create(alias)
            level = self._security_level(alias)
        finally:
            self.destroy(alias)

        names = {2: "strongbox", 1: "trusted environment"}
        if level not in names:
            raise VaultUnavailable("this phone's keystore keeps keys in software")
        self._strongbox = level == 2
        return names[level]

    def _security_level(self, alias: str) -> int:
        key = self._key(alias)
        factory = jclass("javax.crypto.SecretKeyFactory").getInstance(key.getAlgorithm(), _KEYSTORE)
        info_class = jclass("java.lang.Class").forName("android.security.keystore.KeyInfo")
        info = factory.getKeySpec(key, info_class)
        try:
            # Android 12 and later say it outright.
            return int(info.getSecurityLevel())
        except Exception:  # noqa: BLE001 - older API level
            return 1 if info.isInsideSecureHardware() else 0
