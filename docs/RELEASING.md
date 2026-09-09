# Releasing hiberna

A release is cut by pushing a tag. GitHub Actions builds it, signs it, and opens
a **draft** release for review; nothing becomes public until a human publishes
it.

## The signing identity

Every channel hiberna ships on carries the same certificate:

- Keystore: `~/.keystores/hiberna/hiberna-release.jks` (PKCS12, RSA 4096,
  SHA256withRSA, expires 2054-01-25)
- Alias: `hiberna`
- Certificate SHA-256:
  `08:6E:7F:E1:E8:32:46:D7:B6:68:7A:D8:FE:BE:62:AD:9D:39:A5:5E:A1:67:6B:1A:E5:3B:08:9F:8C:1D:67:5C`

**Losing the keystore or its password is unrecoverable.** No later build can
update an installed copy. Every user has to uninstall hiberna — losing their
presets and their snapshots — before they can install again. Keep an encrypted
copy of the `.jks` file somewhere other than the build machine, and keep the
password in a password manager, not in this repository.

To check that a published APK really carries this certificate:

```bash
apksigner verify --print-certs hiberna-v1.0.0.apk
```

The `SHA-256 digest` it prints for the signer certificate must equal the
fingerprint above. This works without the keystore, so anyone can verify it.

## One-time repository setup

Four repository secrets are required, under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | `hiberna` |
| `KEY_PASSWORD` | Key password (same as the keystore password) |

Encode the keystore:

```bash
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.keystores\hiberna\hiberna-release.jks"))

# macOS or Linux
base64 -w0 ~/.keystores/hiberna/hiberna-release.jks
```

Then, with the GitHub CLI:

```bash
gh secret set KEYSTORE_BASE64 < keystore.b64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
```

Delete the `.b64` file afterwards. It is the keystore in plain text.

## Cutting a release

1. Bump both values in `app/build.gradle.kts`:
   - `versionCode` — a plain integer, incremented by one every release, never
     reused and never decreased. F-Droid and Android both refuse an update whose
     `versionCode` did not rise.
   - `versionName` — the human version, for example `1.1.0`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. The
   filename is the **`versionCode`**, not the version name, so `versionCode = 2`
   is `2.txt`. F-Droid and IzzyOnDroid read the release notes from this file.
3. Commit, then tag. **The tag must be `v` plus `versionName` exactly** — the
   workflow asserts this and fails before building if they disagree, because a
   release whose tag and manifest differ cannot be rebuilt from its own tag.

   ```bash
   git commit -am "Release 1.1.0"
   git tag v1.1.0
   git push origin main --tags
   ```
4. Watch the run: `gh run watch`.
5. Review the draft release at **Releases**, write the notes, and publish.

The draft carries three assets:

- `hiberna-v<version>.apk` — the signed APK
- `mapping-v<version>.txt` — the R8 mapping file. The release build is
  obfuscated, so a user-submitted stack trace is unreadable without it.
- `SHA256SUMS` — checksums for both

## Re-running a failed release

Do not move a tag that has already been pushed; a tag that changes meaning
breaks every checksum and every reproducible build published against it. Instead
re-run the workflow against the existing tag:

```bash
gh workflow run Release -f tag=v1.1.0
```

If a build was already published and is broken, bump `versionCode` and
`versionName` and cut a new release. Deleting a published release does not
un-install it from anyone's phone.

## Building a signed APK locally

Normally unnecessary — CI is the release path. When CI is unavailable, create
`keystore.properties` at the repository root (it is gitignored, and must stay
so):

```properties
storeFile=C:/Users/<you>/.keystores/hiberna/hiberna-release.jks
storePassword=<password>
keyAlias=hiberna
keyPassword=<password>
```

Then:

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

The same values can be passed as the environment variables
`HIBERNA_KEYSTORE_FILE`, `HIBERNA_KEYSTORE_PASSWORD`, `HIBERNA_KEY_ALIAS` and
`HIBERNA_KEY_PASSWORD`, which take precedence over the file.

**With no keystore configured at all, `assembleRelease` still succeeds and
produces an unsigned APK.** That is deliberate: F-Droid's build server and every
outside contributor build from a plain checkout, and they sign with their own
key. An unsigned APK will not install on a phone — use `assembleDebug` for that.

## What the release build checks

`assembleRelease` depends on `verifyShizukuSeeds`, which reads R8's
`seeds.txt` and fails the build if the `rikka.shizuku.**` keep rule stopped
matching. R8 does not warn when a keep rule goes dead, and the privileged entry
point is reached by reflection, so without this check a release could install,
launch, and silently change nothing. If it fails, fix `proguard-rules.pro`
rather than disabling the task.

## After publishing

The store channels are updated separately — see
[DISTRIBUTION.md](DISTRIBUTION.md). In short: IzzyOnDroid picks up new GitHub
releases on its own once hiberna is listed; F-Droid builds from the tag on its
own schedule.
