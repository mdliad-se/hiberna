# Distributing hiberna

Three channels, one signing key. Every APK a user can install carries the
certificate recorded in [RELEASING.md](RELEASING.md), so a user can move between
channels without uninstalling and losing their presets and snapshots.

| Channel | Who builds it | Who signs it | Effort |
|---|---|---|---|
| GitHub Releases | GitHub Actions | hiberna's key | Automatic on tag |
| IzzyOnDroid | Takes the GitHub release APK, rebuilds to verify | hiberna's key | One inclusion request |
| F-Droid official | F-Droid, from the git tag | hiberna's key, via a reproducible build | One merge request, weeks of review |

**Droid-ify, Neo Store and the F-Droid client are not separate submissions.**
They are clients that read the F-Droid and IzzyOnDroid repositories. Getting
into those two repositories is what puts hiberna in all of them. There is nobody
to contact at Droid-ify.

Google Play and Accrescent are deliberately out of scope for now. Play would
need an App Bundle, a paid developer account and a data-safety declaration;
Accrescent needs a separate application to be accepted.

## Before submitting anywhere

Screenshots are missing and both repositories effectively require them. They
must be captured on a real device, because Shizuku does not work on an emulator
and a screenshot taken there would show only the "Shizuku not available" state.
See `fastlane/metadata/android/en-US/images/phoneScreenshots/README.txt` for
naming and what not to capture. GitHub Releases is unaffected and can ship
first.

## 1. GitHub Releases

Covered by [RELEASING.md](RELEASING.md). Nothing further is needed — this is the
channel every other one reads from.

Point users who want automatic updates without a store at
**[Obtainium](https://github.com/ImranR98/Obtainium)**: they add
`https://github.com/mdliad-se/hiberna` as an app and Obtainium tracks the
releases directly. Worth naming in the README, since it is the fastest path for
early users while the store submissions are in review.

## 2. IzzyOnDroid

The usual first store, and effectively a rehearsal for F-Droid: it applies the
same scanner but reviews in days rather than weeks, and it publishes hiberna's
own signed APK from the GitHub release.

Requirements, all already met apart from screenshots:

- Free software licence with the licence file in the repository — GPL-3.0.
- No proprietary dependencies and no tracking libraries. Izzy runs a scanner;
  hiberna has no network permission at all, so there is nothing to find.
- `fastlane/metadata/android/en-US/` present in the repository — done.
- A GitHub release with the APK attached, named predictably — done.

To submit, open an inclusion request on the IzzyOnDroid repository issue tracker
at <https://gitlab.com/IzzyOnDroid/repo/-/issues> (needs a GitLab account).
State:

- the repository URL, `https://github.com/mdliad-se/hiberna`
- that releases carry a signed APK attached to a GitHub release
- that fastlane metadata is in the repository
- the licence, GPL-3.0-or-later
- that the app requires Shizuku, so it cannot be exercised on an emulator

After inclusion, new GitHub releases are picked up automatically. No action per
release.

## 3. F-Droid official

F-Droid builds from the git tag on its own infrastructure. By default it signs
with its own key; hiberna instead submits a **reproducible build**, where
F-Droid rebuilds the tag, byte-compares the result against the published APK,
and then distributes hiberna's own signed APK. That is what keeps one signature
across all three channels.

### The recipe

Submitted as `metadata/com.jinatra.hiberna.yml` in a merge request against
<https://gitlab.com/fdroid/fdroiddata>:

```yaml
Categories:
  - System
License: GPL-3.0-or-later
AuthorName: Jinatra Ltd.
WebSite: https://github.com/mdliad-se/hiberna
SourceCode: https://github.com/mdliad-se/hiberna
IssueTracker: https://github.com/mdliad-se/hiberna/issues
Changelog: https://github.com/mdliad-se/hiberna/releases

RepoType: git
Repo: https://github.com/mdliad-se/hiberna.git

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - yes

AllowedAPKSigningKeys: 086e7fe1e83246d7b6687ad8febe62ad9d39a55ea1676b1ae53b089f8c1d675c

Binaries: https://github.com/mdliad-se/hiberna/releases/download/v%v/hiberna-v%v.apk

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.0
CurrentVersionCode: 1
```

The three lines that make this a developer-signed reproducible build, rather
than an ordinary F-Droid-signed one:

- `AllowedAPKSigningKeys` — the certificate SHA-256 from
  [RELEASING.md](RELEASING.md), lowercase hex with the colons removed. F-Droid
  refuses any APK not signed by it.
- `Binaries` — where F-Droid fetches the published APK to compare against. `%v`
  expands to `versionName` and `%c` to `versionCode`, which is why the release
  asset is named `hiberna-v<version>.apk` and why the tag must match
  `versionName`.
- `commit: v1.0.0` — the tag, not a branch. The published APK and the compared
  source must be the same commit.

### Verify reproducibility before submitting

Do this first. A merge request that does not reproduce will sit unmerged.

```bash
git clone https://gitlab.com/fdroid/fdroidserver
# then, in a checkout of fdroiddata with the recipe added:
fdroid build --verbose --on-server com.jinatra.hiberna:1
fdroid verify com.jinatra.hiberna
```

`fdroid verify` compares the rebuilt APK against the one fetched from
`Binaries`. Only the signature block may differ.

### If the build fails on the JDK

The build declares `jvmToolchain(21)` and deliberately does not
auto-provision a JDK, so F-Droid's buildserver must already have JDK 21 on
`JAVA_HOME`. If its default is older, the recipe needs the build step to select
it explicitly, in place of `gradle: [yes]`:

```yaml
    build:
      - export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
      - $$GRADLE$$ :app:assembleRelease
```

Confirm the exact path against the buildserver image rather than assuming it —
`fdroid build` locally will show it. If this turns into a fight, the honest
alternative is lowering the toolchain to 17, which the source is compatible
with (`jvmTarget` is already `JVM_17`); that is an application change and
belongs in its own piece of work, not in the recipe.

### What can break the byte-match later

`isMinifyEnabled = true` means R8 produces the release APK, and **R8's output
changes between Android Gradle Plugin versions.** The frozen toolchain is
therefore part of the release contract, not an incidental detail:

- Android Gradle Plugin 8.9.0
- Kotlin 2.1.0
- Gradle 8.13
- JDK 21
- compileSdk 36

After upgrading any of these, re-run `fdroid verify` before cutting the next
release. If it no longer reproduces, the F-Droid update stalls until it is
fixed. The alternative — dropping `Binaries` and `AllowedAPKSigningKeys` so
F-Droid signs with its own key — is a signature change that forces every
existing F-Droid user to uninstall, so it is not a quick escape hatch.

`dependenciesInfo { includeInApk = false }` in `app/build.gradle.kts` is also
load-bearing here: the Google-signed dependency blob AGP embeds by default is
not reproducible and F-Droid rejects it. Do not remove it.

### Per-release maintenance

Each new release needs a `Builds:` entry appended and `CurrentVersion` /
`CurrentVersionCode` bumped — a small merge request against fdroiddata, or
handled by `AutoUpdateMode: Version` once F-Droid trusts the tag pattern.

## Answering "which one should I tell users about?"

Until the store submissions land, the README should point at GitHub Releases
plus Obtainium. Once IzzyOnDroid is live, lead with that — it reaches Droid-ify
and Neo Store users, and updates arrive automatically. F-Droid official is worth
the wait for reach, not for speed.
