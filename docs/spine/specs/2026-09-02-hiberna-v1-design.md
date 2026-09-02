# hiberna v1 — design

- **Date:** 2026-09-02
- **Stage:** FRAME (approved)
- **Package:** `com.jinatra.hiberna`
- **Owner:** Jinatra Ltd.
- **Config contract:** `.spine/config.md`

## 1 · Context

Android restricts background execution per app, but the controls are buried
one-app-at-a-time in Settings. Doing it for a full device is dozens of screens
of tapping. hiberna exposes those same controls in one list, using Shizuku for
the privilege it needs, with no computer involved.

adil192's NoMoreBackground solves the same problem desktop-first, over USB ADB.
hiberna is **not a fork of it** — see section 2.

## 2 · Ownership and licensing

hiberna is a **clean reimplementation**. `../no_more_background/` is retained as
a behavioural reference only: which shell commands exist and roughly what they
return. Ideas are not copyrightable and the shell commands are public Android
platform surface.

Hard rules:

- No Kotlin or Dart copied from the reference.
- No i18n strings copied from the reference.
- UI and architecture derived from this document, not transcribed from theirs.
- No GPL-3 inheritance. Licence for hiberna is Jinatra's own choice, to be set
  before the first public release.

## 3 · Goals / non-goals

**v1 goals**

1. Shizuku gate — detect the service, request permission, treat "not running" as
   a first-class screen with pairing guidance, never an error dialog.
2. App list — real icons and labels from `PackageManager`, search, system/user
   filter, filter by current restriction state.
3. Three levers per app, surfaced as a tri-state plus an independent data toggle.
4. Bulk multi-select and named presets.
5. Sensitive-app guardrail, excluded from bulk by default.

**Non-goals in v1** — desktop / USB ADB, root via libsu, any locale but English,
`batterystats` ranking, scheduling, export/import, snapshot/undo, **Google Play
distribution**.

Play's policy makes a Shizuku-driven `appops` app a non-starter. Distribution is
F-Droid, Droidify and GitHub releases. This is a decision, not an oversight.

## 4 · Vocabulary

| Term | Meaning |
|---|---|
| **Lever** | One of the three shell-level controls in section 6 |
| **Policy** | One package's desired state across all levers |
| **Preset** | A named policy applied to many packages |
| **Snapshot** | Point-in-time capture of every policy (v2) |
| **Hibernate** | **Never used as a UI verb.** Android ships its own "App Hibernation" (auto-revoking permissions from unused apps), which hiberna does not do. Buttons say **Restrict** |

## 5 · Architecture

Single module, layered, MVVM + `StateFlow`. Multi-module and strict MVI were
both considered and rejected: at ~5k LOC solo-maintained, the only boundary that
must be rigid is the privilege seam, and that is an interface, not a module.

```
com.jinatra.hiberna
├── HibernaApp.kt              Application, dependency wiring, boot assertion
├── shell/
│   ├── ShellBackend.kt        interface — THE privilege seam
│   ├── ShizukuShellBackend.kt real implementation
│   ├── FakeShellBackend.kt    scripted responses for tests
│   └── ShellResult.kt         exit code + stdout + stderr
├── privilege/ShizukuGate.kt   service detection, permission request, state
├── apps/                      InstalledApp, InstalledAppRepository
├── policy/                    AppPolicy, BackgroundActivity, PolicyRepository, PolicyApplier
├── guardrail/                 Sensitivity, SensitivityDetector
├── preset/                    Preset, PresetRepository
├── data/                      DataStore wiring, Json config
└── ui/
    ├── theme/                 JinatraTheme, tokens, Archivo/Inter/JetBrainsMono
    ├── components/            BrutalButton, BrutalCard, BrutalCheckbox, BrutalSegmented
    └── screens/               GateScreen, AppListScreen, AppDetailSheet, PresetScreen
```

**The seam.** Every privileged operation goes through `ShellBackend`. Nothing
else in the codebase may invoke Shizuku, `Runtime.exec`, or a shell. This is what
makes the app testable without a paired device, and it is asserted at boot.

```kotlin
interface ShellBackend {
    val isAvailable: Boolean
    suspend fun exec(command: List<String>): ShellResult
}
```

## 6 · Lever mapping

| Tri-state (Android's own wording) | appops RUN_ANY_IN_BACKGROUND | deviceidle whitelist |
|---|---|---|
| **Restricted** | `ignore` | absent |
| **Optimized** | `allow` | absent |
| **Unrestricted** | `allow` | present |

Background data is independent of the tri-state:

```
cmd netpolicy add    restrict-background-blacklist <uid>
cmd netpolicy remove restrict-background-blacklist <uid>
```

**uid is resolved fresh from `PackageManager` at apply time.** Policy is stored
keyed on package name. Storing uid is a latent bug: uids are reassigned on
reinstall, so a stored uid silently begins pointing at a different app.

### Read commands

| Purpose | Command |
|---|---|
| Which apps are appops-restricted | `cmd appops query-op RUN_ANY_IN_BACKGROUND ignore` |
| Which apps are battery-whitelisted | `dumpsys deviceidle whitelist` |
| Which uids have background data blocked | `cmd netpolicy list restrict-background-blacklist` |

### Write commands

| Purpose | Command |
|---|---|
| Set appops state | `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` or `allow` |
| Add battery whitelist | `dumpsys deviceidle whitelist +<pkg>` |
| Remove battery whitelist | `dumpsys deviceidle whitelist -<pkg>` |
| Add data restriction | `cmd netpolicy add restrict-background-blacklist <uid>` |
| Remove data restriction | `cmd netpolicy remove restrict-background-blacklist <uid>` |

**Output formats are NOT specified here on purpose.** Every parser is written
against output captured by the spike in section 9 on the actual verification
device, not against assumptions. Guessing a `dumpsys` format is how this app
ships a silent no-op.

## 7 · Data model

```kotlin
enum class BackgroundActivity { RESTRICTED, OPTIMIZED, UNRESTRICTED }

@Serializable
data class AppPolicy(
    val packageName: String,
    val backgroundActivity: BackgroundActivity,
    val restrictBackgroundData: Boolean,
)

data class InstalledApp(          // not serialized; rebuilt from PackageManager
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val sensitivity: Sensitivity,
)

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val backgroundActivity: BackgroundActivity,
    val restrictBackgroundData: Boolean,
    val skipSensitive: Boolean = true,
)
```

Persistence — no database:

- DataStore Preferences for settings and the sensitive-app override set.
- `kotlinx.serialization` JSON for presets.
- v2 snapshots as individual files under `filesDir/snapshots/`, so the export
  format and the storage format are the same bytes.

## 8 · Guardrail

Bulk-restricting indiscriminately breaks messaging notifications and alarms, and
users will not connect the two events. Sensitive apps are **detected**, not
hardcoded, so detection survives across devices and locales:

| Signal | Source |
|---|---|
| SMS / dialer / assistant / home holders | `RoleManager` |
| Alarm apps | Intent-filter query for `SET_ALARM` |
| Authenticators | `AccountManager.getAuthenticatorTypes()` |
| Legitimate background needs | Declared foreground-service types: `location`, `health`, `mediaPlayback` |
| Everything else | Small static fallback list |

```kotlin
enum class Sensitivity { NONE, LIKELY_BREAKS, USER_OVERRIDDEN }
```

`LIKELY_BREAKS` apps are visibly flagged and skipped by bulk operations when
`Preset.skipSensitive` is true. Always overridable per app; overrides persist.

## 9 · Risks

| Risk | Severity | Mitigation |
|---|---|---|
| appops/netpolicy/deviceidle changed or restricted on API 37 | **Project-defining** | Spike, below |
| Shizuku lags future Android releases | High | `ShellBackend` stays swappable; root becomes the escape hatch |
| Bulk restriction breaks notifications | High | Section 8 guardrail |
| `dumpsys` output format drift between versions | Medium | Parsers tested against captured fixtures; parse failure surfaces as an explicit error, never as "nothing restricted" |
| GPL contamination from the reference clone | Medium | Section 2 rules, pinned in `.spine/config.md` |

### Build task 1 — privilege spike (blocks everything)

On the Pixel 10 Pro over wireless debugging, before any UI exists:

1. Run each read command in section 6; capture raw output verbatim as test
   fixtures.
2. Run each write command against one harmless app; confirm the change appears
   in Settings and survives a reboot.
3. Confirm Shizuku can execute them — activation alone is not proof, and the
   reported Android 17 Shizuku bug concerns app *detection*.

If any lever is dead on API 37, FRAME reopens and the Android-only decision in
the config contract is revisited before a line of UI is written.

## 10 · Testing

- `ShellBackend` + `FakeShellBackend` make every policy path unit-testable with
  no device.
- Robolectric covers `PackageManager` and `RoleManager` logic on the JVM.
- Compose UI tests via `createComposeRule()`, which is a JUnit4 `TestRule` —
  that constraint is what fixes the runner choice.
- Turbine for `StateFlow` assertions.
- One instrumented smoke test exercises the real Shizuku backend on device.
- Parser tests run against the section 9 fixtures.

**Boot assertion.** In a release build, `HibernaApp` asserts the resolved
`ShellBackend` is `ShizukuShellBackend` and calls `error()` if not. The failure
this app cannot afford is a build that appears to restrict apps while touching
nothing.

## 11 · UI

Jinatra Brand Guidelines v1.1 — 3px Ink borders, hard offset shadows
(3px controls / 6px cards / 10px for one hero element), radius 0, Archivo
display, Inter body, JetBrains Mono for data. Product colour Night Indigo
`#2F2BD1`. Assets and tokens live in `../brand/`.

The app icon stays flat, with no Ink border and no shadow, per v1.1 section 14.

| Screen | Content |
|---|---|
| **Gate** | Shizuku state, pairing walkthrough, retry. Not an error dialog |
| **App list** | Search, filters, multi-select mode, per-row tri-state, sensitivity flag |
| **App detail** | Tri-state, data toggle, sensitivity explanation, open in Settings |
| **Presets** | Create / edit / apply, `skipSensitive` toggle |
| **Bulk bar** | Selection count, preset picker, count of skipped sensitive apps |

## 12 · Milestones

| Version | Contents |
|---|---|
| **v1** | Section 3 goals |
| **v2** | Export / import policy, snapshot and undo |
| **v3** | `batterystats` offender ranking, break-warnings from real usage data |

## 13 · Open decision, deferred to PLAN

**DI mechanism** — manual container vs Hilt. Manual is adequate for a single
module and avoids KSP; Hilt is the ecosystem default and better if the module
count ever grows. Confidence in manual is only ~80%, below the bar for silently
assuming it, but it is cheap and local to reverse — so it is settled at PLAN
time rather than spending a FRAME round on it.
