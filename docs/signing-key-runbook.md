# Release signing key: backup and recovery

Zamolxis ships as APKs from GitHub Releases, installed directly or through Obtainium.
There is no Play Store account behind it and no Play App Signing, so **the release
keystore is the only thing that makes an update an update**. Android refuses to install
an APK over an existing one signed by a different key. Lose the key and every user has
to uninstall and reinstall by hand.

Since the message database became encrypted at rest under a device-bound key, that
uninstall also destroys their message history permanently — the database cannot be
carried across a reinstall (see `SECURITY.md`). Losing the signing key is therefore not
an inconvenience for the maintainer; it is data loss for every user.

## Where the key lives today

CI holds it as four GitHub Actions repository secrets, used by
[`.github/workflows/release.yml`](../.github/workflows/release.yml):

| Secret | Contents |
|---|---|
| `KEYSTORE_FILE` | the keystore, base64-encoded, single line |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | alias of the signing key inside the store |
| `KEY_PASSWORD` | password of that key |

`app/build.gradle.kts` reads all four from the environment, base64-decodes `KEYSTORE_FILE`
into `app/build/keystore/release.keystore`, and builds a `release` signing config from
them. When any of the four is missing it prints "Release signing not configured" and
builds unsigned — that is why a local release build works without them.

The release workflow then runs `keytool -list -v` against the decoded keystore and puts
the certificate's SHA-256 and SHA-1 fingerprints into the release notes. That published
fingerprint is what a user checks before trusting an APK, and it is derived from this key.

**Nothing about the key belongs in the repository.** `.gitignore` covers `*.jks`,
`*.keystore`, `*.keystore.b64`, `*.p12` and `keystore.properties`; no keystore is tracked.
Do not add passwords to any file here, including this one.

## Backing it up

Back up two things, and treat them as equally critical: the keystore file itself and the
three secrets that open it. A keystore without its passwords is as lost as no keystore.

1. Write the passwords and the alias into a plain text file, next to the keystore, in a
   working directory that is not inside the repository.
2. Encrypt the pair. Either tool is fine; pick one and stay with it:

   ```bash
   # age, with a passphrase you can reproduce from memory or a password manager
   age -p -o zamolxis-signing-backup.tar.gz.age zamolxis-signing-backup.tar.gz

   # or gpg, symmetric
   gpg --symmetric --cipher-algo AES256 zamolxis-signing-backup.tar.gz
   ```

3. Put the encrypted archive in **two locations that do not fail together**. Two folders
   on the same laptop is not two locations; neither is two cloud accounts reachable with
   the same password. A hardware token or paper copy of the decryption passphrase, stored
   separately from both, is the point of the exercise.
4. Delete the unencrypted archive and the plaintext password file.

Verify the backup by restoring it somewhere else, at least once, before you need it. An
untested backup is a belief, not a backup.

## Restoring

1. Decrypt one copy of the archive and unpack the keystore.
2. Confirm it is the right key before doing anything else — the fingerprint must match the
   one published with the last release:

   ```bash
   keytool -list -v -keystore release.keystore -alias <alias>
   ```

3. Re-encode for CI and set the four secrets again:

   ```bash
   base64 -w 0 release.keystore > keystore.b64
   ```

   Paste the contents of `keystore.b64` into `KEYSTORE_FILE`, and set
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Delete `keystore.b64` afterwards.
4. Cut a test release and check that the fingerprint in the release notes matches the
   previous release's. If it differs, the restore used the wrong key — stop and do not
   publish, because that APK will not install over anyone's existing app.

## If the key is lost

There is no recovery, and it is worth being blunt about the options because none is good:

- **Rotation does not save you.** APK signature scheme v3 supports rotation, but the
  rotation proof must be signed by the *old* key. Without it there is nothing to rotate
  from. Direct-install and Obtainium users additionally handle rotated lineages poorly.
- **Publishing under a new key means every user reinstalls**, loses their message history
  as described above, and has no cryptographic way to tell your new key from an impostor's.
  If it happens, announce the new fingerprint through every channel the project controls,
  well before the first release signed with it, and expect the transition to be the moment
  an attacker would choose to impersonate the project.

## If the key is compromised

Treat a leaked keystore as a supply-chain incident, not a maintenance task: anyone holding
it can sign an APK that installs silently over the real one. Publish the fact quickly, stop
signing with the key, and go through the new-key transition above. The published
fingerprints in past release notes remain useful evidence for which builds predate the
leak.
