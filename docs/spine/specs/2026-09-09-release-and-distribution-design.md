# hiberna — release signing and open-source distribution

**Date:** 2026-09-09
**Stage:** FRAME (approved)
**Scope:** repository release infrastructure. No application behaviour changes.

## Problem

hiberna is feature-complete at versionName 1.0.0 / versionCode 1 and unreleased. The repository has no signing
configuration, no continuous integration, and no store metadata, so there is
nothing installable to hand a user and nothing submittable to a store. Three
things are missing:

1. A signed release APK, produced the same way every time.
2. A signing identity that survives across every channel the app is published
   on, because switching signatures forces users to uninstall.
3. The metadata and build recipes the open-source Android repositories require.

## Decisions taken during framing

| Decision | Chosen | Rejected |
|---|---|---|
| Build host | GitHub Actions | Local toolchain (nothing committed for the next release) |
| Signing key | New PKCS12 keystore, generated 2026-09-09 | Reusing an existing key (none exists) |
| Channels | GitHub Releases, IzzyOnDroid, F-Droid official | Accrescent, Play Store |
| F-Droid signature | Reproducible build, published under hiberna's own key | F-Droid's key (forces an uninstall to change channel) |
| Release trigger | Tag push creates a draft release, plus a manual dispatch restricted to re-running an existing tag | Immediate publish; keeping the key off CI; a dispatch that can build an arbitrary ref |

Two lower-stakes points were settled without a question, and are recorded here
because the build depends on both: the git tag must equal `versionName` or the
release fails, and `versionCode` stays hand-edited in `app/build.gradle.kts`.
Deriving `versionCode` in continuous integration would desynchronise it from the
value F-Droid reads out of the tagged source, which is the one thing a
reproducible build cannot tolerate.

## Signing identity

Generated on 2026-09-09 and held outside the repository:

- File: `~/.keystores/hiberna/hiberna-release.jks`
- Store type: PKCS12, RSA 4096, SHA256withRSA, 10000 days (expires 2054-01-25)
- Alias: `hiberna`
- Certificate SHA-256:
  `08:6E:7F:E1:E8:32:46:D7:B6:68:7A:D8:FE:BE:62:AD:9D:39:A5:5E:A1:67:6B:1A:E5:3B:08:9F:8C:1D:67:5C`

This certificate fingerprint is the app's identity on every channel. Losing the
keystore or its password is unrecoverable: no future build can update an
installed copy, and every user has to uninstall — losing their presets and their
snapshots — before they can install again. The fingerprint is recorded here so a
published APK can be checked against it independently of the keystore.

## Section A — signing configuration

`app/build.gradle.kts` gains a `release` signing configuration whose material is
resolved in this order:

1. Environment variables `HIBERNA_KEYSTORE_FILE`, `HIBERNA_KEYSTORE_PASSWORD`,
   `HIBERNA_KEY_ALIAS`, `HIBERNA_KEY_PASSWORD` (used by continuous integration).
2. A `keystore.properties` file at the repository root, untracked, with the keys
   `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (used locally).

`HIBERNA_KEY_PASSWORD` falls back to `HIBERNA_KEYSTORE_PASSWORD` when unset,
because the generated keystore uses one password for both.

**Invariant: an absent keystore must not fail the build.** When no material is
found, the release variant is assembled unsigned. This is not a convenience —
F-Droid and every outside contributor build from a plain checkout, and a release
build that requires a private key would make the app unbuildable by anyone but
its author, which is the failure mode GPL-3 exists to prevent.

`.gitignore` gains `keystore.properties`, `*.jks` and `*.keystore`, so the
material cannot be committed by accident.

## Section B — reproducibility

F-Droid must be able to rebuild the tagged source and obtain an APK that matches
the published one byte for byte, before it will distribute an APK signed with
hiberna's key. Two changes and one freeze:

- `dependenciesInfo { includeInApk = false; includeInBundle = false }`. Android
  Gradle Plugin embeds a Google-signed blob of dependency metadata by default.
  It is opaque, it is not reproducible, and F-Droid rejects it.
- Nothing in the build may read the clock, the hostname, or the build path.
- The toolchain is frozen and must be quoted verbatim in the F-Droid recipe:
  Android Gradle Plugin 8.9.0, Kotlin 2.1.0, Gradle 8.13, JDK 21, compileSdk 36.
  These are already pinned exactly in `gradle/libs.versions.toml` and
  `gradle/wrapper/gradle-wrapper.properties`; the work here is recording them,
  not changing them.

R8 is the fragile part. `isMinifyEnabled = true` means the output depends on the
exact AGP version, so any AGP upgrade invalidates the byte-match and needs the
reproducibility to be re-verified before the next F-Droid submission. The
existing `verifyShizukuSeeds` task is unaffected and still gates every release.

## Section C — release workflow

`.github/workflows/release.yml`, triggered by a pushed tag matching `v*`:

1. Check out the tag.
2. Set up JDK 21 (Temurin) and the Gradle cache.
3. Assert the tag equals `versionName` in `app/build.gradle.kts`. On mismatch,
   fail before anything is built. A release whose tag and manifest disagree
   cannot be reproduced from its own tag.
4. `./gradlew test`.
5. Decode the `KEYSTORE_BASE64` secret into a file under the runner's temporary
   directory.
6. `./gradlew assembleRelease`, which already depends on `verifyShizukuSeeds`.
7. Produce `SHA256SUMS` for the APK.
8. Create a **draft** GitHub release holding the APK, `mapping.txt` and
   `SHA256SUMS`.

Required repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`.

`mapping.txt` is published deliberately. R8 obfuscates the release build, so
without the mapping file a user-submitted stack trace is unreadable. It reveals
nothing that the GPL-3 source does not already.

The release is a draft rather than published so a human sees the artefact before
the public does. hiberna asks users for shell-level privilege; an automatic
publish of an unreviewed build is the wrong default for a tool with that reach.

The workflow also accepts a `workflow_dispatch` with a tag input, so a failed
release can be re-run against a tag that has already been pushed. Moving a
pushed tag would invalidate every checksum and every reproducible build already
published against it, which makes re-running the only safe recovery. The
dispatch path passes the same tag-versus-`versionName` assertion as a tag push,
and refuses to touch a release that is no longer a draft.

## Section D — store metadata

`fastlane/metadata/android/en-US/`, read by both IzzyOnDroid and F-Droid:

- `title.txt` — `hiberna`
- `short_description.txt` — 80 characters maximum, a hard F-Droid limit
- `full_description.txt` — the store listing body
- `images/icon.png` — from `assets/icon-512.png`
- `images/phoneScreenshots/` — screenshots, numerically named
- `changelogs/1.txt` — keyed by `versionCode`, so v1.0.0 is `1.txt`

## Section E — documentation

- `docs/RELEASING.md` — cutting a release, the four secrets, keystore backup and
  the cost of losing it, and how to sign locally if CI is unavailable.
- `docs/DISTRIBUTION.md` — the `fdroiddata` metadata recipe for
  `com.jinatra.hiberna` declaring `Binaries:` and `AllowedAPKSigningKeys:`
  (fdroiddata has no `Reproducible:` field; those two keys are what make a
  build developer-signed and reproducible), how to verify the build
  locally with `fdroid build` before submitting, the IzzyOnDroid submission
  procedure, Obtainium setup for GitHub-release users, and a note that Droid-ify
  and Neo Store are clients that read these repositories rather than separate
  submissions.
- README — an install section pointing at the channels.

## Out of scope

Google Play and Android App Bundle output. Accrescent. A pull-request
continuous-integration workflow. Automating `versionCode`. Filing the F-Droid
merge request and the IzzyOnDroid request, both of which need the maintainer's
own accounts — this work produces the exact recipe text to file.

## Known gap

Neither IzzyOnDroid nor F-Droid accepts a listing without screenshots in
practice, and none can be produced here: Shizuku requires a real device, so an
emulator cannot show the app in a working state. `images/phoneScreenshots/` is
scaffolded with its naming documented, and screenshots must be captured on the
Pixel 10 Pro verification device before either submission is filed. GitHub
Releases is unaffected and can ship first.
