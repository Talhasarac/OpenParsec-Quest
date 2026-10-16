# Release signing and migration

## Retired public key

The former `openparsec-release.keystore` and its passwords were public. The key
used for v0.4.9-quest and earlier is permanently compromised: anyone with a copy
can sign an APK with that identity. This exposure is not a Parsec account-password
leak. Removing the file from the current branch does not revoke copies or remove
it from Git history, forks, or old tags.

The retired certificate's SHA-256 fingerprint is:

```text
F9:56:CC:01:74:4D:2C:C1:4E:C2:3B:E1:7E:E0:26:40:54:57:2F:F9:88:01:43:EE:FB:86:3F:6D:FD:95:C1:E7
```

Release builds reject its public key, including copies with a different password,
alias, or reissued certificate. Do not restore it from history, put it into Actions
secrets, or use an old tag's release workflow to publish another APK.

## User migration

The replacement private signing identity has certificate SHA-256:

```text
D4:89:B2:5E:4B:58:EA:31:ED:8A:8F:C3:58:1F:54:4E:62:87:72:96:A9:21:C0:3C:72:0A:94:84:E6:6C:E2:F1
```

The same digest is stored in `signing/release-certificate.sha256`; the release
workflow verifies the built APK against it before publishing. This identifies
future releases built with this workflow, not existing release assets.

This migration keeps the package ID `com.openparsec.quest` and deliberately does
not create a signing lineage from the compromised key. An APK signed with the
new key cannot update an old installation in place. Android will report
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` if you try `adb install -r` over the old app.

1. Download the first release signed with the new certificate from this
   repository's [GitHub Releases](https://github.com/Talhasarac/OpenParsec-Quest/releases).
2. Record any settings you want to restore. Uninstalling removes local app data,
   settings, and login state; it does not delete your Parsec account.
3. Uninstall the old OpenParsec Quest app, then install the new APK and log in again.
4. Later releases signed with the same new private key can update normally.

With Android SDK Build Tools installed, check a downloaded APK's signature:

```bash
apksigner verify --verbose --print-certs path/to/openparsec-quest2.apk
```

Compare `Signer #1 certificate SHA-256 digest` with the new fingerprint above
(ignoring colons and case). The old fingerprint is not evidence of authenticity.

## Local builds

Contributors can run `./gradlew assembleDebug` without any release secrets.
For release builds, supply all four environment variables:

| Variable | Value |
| --- | --- |
| `OPENPARSEC_KEYSTORE_PATH` | Private JKS or PKCS12 keystore path; absolute paths are recommended, relative paths resolve from the repository root. |
| `OPENPARSEC_STORE_PASSWORD` | Keystore password. |
| `OPENPARSEC_KEY_ALIAS` | Alias of the private signing key. |
| `OPENPARSEC_KEY_PASSWORD` | Private-key password. |

Load credentials from a protected file outside the checkout or from a password
manager. Do not put passwords into tracked Gradle files, shell commands saved in
history, build logs, or command-line Gradle properties. Then run:

```bash
./gradlew :app:verifyReleaseSigning
./gradlew assembleRelease
```

Missing credentials, an unreadable keystore, invalid credentials, or the exposed
key fail the build. There is no unsigned or debug-key fallback for release tasks.
The verification task runs before release builds and signing validation, including
APK and app-bundle builds. IDE sync and debug builds need no release credentials.

## Maintainer key storage and GitHub Actions

Keep one stable private key for official releases. Keep encrypted backups of the
keystore and store its passwords in a password manager. Losing the key requires
another migration; changing only the passwords does not replace an exposed key.

The tag-triggered release workflow uses these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `OPENPARSEC_KEYSTORE_BASE64` | Base64 encoding of the new private keystore. |
| `OPENPARSEC_STORE_PASSWORD` | Keystore password. |
| `OPENPARSEC_KEY_ALIAS` | Private-key alias. |
| `OPENPARSEC_KEY_PASSWORD` | Private-key password. |

Base64 is only transport encoding, not encryption. Upload it directly to GitHub
Secrets rather than saving it in the repository. After loading the four local
environment variables above, the GitHub CLI can configure the secrets without
printing their values or passing passwords as command arguments:

```bash
base64 < "$OPENPARSEC_KEYSTORE_PATH" | gh secret set OPENPARSEC_KEYSTORE_BASE64 --repo Talhasarac/OpenParsec-Quest
printf '%s' "$OPENPARSEC_STORE_PASSWORD" | gh secret set OPENPARSEC_STORE_PASSWORD --repo Talhasarac/OpenParsec-Quest
printf '%s' "$OPENPARSEC_KEY_ALIAS" | gh secret set OPENPARSEC_KEY_ALIAS --repo Talhasarac/OpenParsec-Quest
printf '%s' "$OPENPARSEC_KEY_PASSWORD" | gh secret set OPENPARSEC_KEY_PASSWORD --repo Talhasarac/OpenParsec-Quest
```

The workflow decodes the keystore into the runner's temporary directory with
owner-only permissions, exposes passwords only to the build step, removes the
keystore even if the build fails, and checks the APK's certificate before release
publication. Never upload the keystore or credential files as workflow artifacts.
New version tags must point to commits containing this private signing workflow.

For a fork, generate your own key outside the checkout (keytool prompts for the
password), configure your own repository's secrets, and replace the pinned
certificate digest and documentation with your identity:

```bash
keytool -genkeypair -storetype PKCS12 -keystore /secure/path/release.p12 \
  -alias release -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000
```

See the official [Android app-signing documentation](https://developer.android.com/studio/publish/app-signing)
and [GitHub Actions secrets documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
