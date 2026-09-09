# hiberna 1.1.0 — state filter, per-app runtime, per-app battery

**Date:** 2026-09-09
**Stage:** FRAME (approved)
**Release:** versionName 1.1.0, versionCode 2

Naming note: the released version is 1.0.0, so this is 1.1.0 by semver.
`2026-09-07-hiberna-v1.1-design.md` is a pre-release design iteration, not a
shipped version, and is unrelated to this number.

## Problem

Three gaps reported from the 1.0.0 build on a real device:

1. There is no way to see which apps are currently restricted and which are
   not. Per-app state is visible on each row through the activity picker, but
   nothing answers "show me everything I have already restricted". The only
   filters are the search field and the system-apps switch.
2. There is no runtime per app, so nothing says which apps are actually being
   used.
3. There is no battery use per app, so nothing says which apps are actually
   costing anything.

Two and three are the missing half of the decision the app exists to support.
hiberna currently shows what *will break* if you restrict an app (the severity
scale) but nothing about what you *gain* by restricting it.

## Decisions taken during framing

| Decision | Chosen | Rejected |
|---|---|---|
| Time window | Since last full charge, for both metrics | 24 hours; a user-selectable window |
| Placement | Compact metrics line on every row, full breakdown in the detail sheet | Detail sheet only; row without detail |
| Usage permission | One-time prompt, then grant via Shizuku, with a revoke control | Silent auto-grant; sending the user to Settings |
| Sorting | Sort control with severity as the unchanged default | Defaulting to battery; no sort control |
| Battery source | Parse `dumpsys batterystats` | Deep-linking to Settings' battery screen |

The battery decision was taken against a recommendation. `dumpsys batterystats`
has an undocumented output format that varies by vendor and Android version, so
this parser will break and will need maintenance. It was accepted deliberately
in exchange for having the numbers inside the app. Section C is built around
containing that cost rather than pretending it does not exist.

Assumptions recorded without a question, because each has only one sane answer
given what the codebase already establishes:

- Unavailable data is always explicit and never rendered as zero. `PolicyReader`
  already refuses to let "could not read" and "nothing restricted" be the same
  value; the same rule applies to both new metrics.
- Metrics refresh on the existing `refresh()`, not on a poll.
- Filter chips carry counts.
- The release adds `fastlane/metadata/android/en-US/changelogs/2.txt`.

## Section A — state filter

`AppListState` gains `stateFilter`, one of `ALL`, `RESTRICTED`, `OPTIMIZED`,
`UNRESTRICTED`, `DATA_BLOCKED`, defaulting to `ALL`.
`AppListViewModel.reproject()` applies it after the existing system-apps and
query filters. No new reads: `AppRowState` already carries `activity` and
`dataBlocked`.

Chip counts are computed after the system-apps filter but **before** the query
filter, so "Restricted 34" does not change while the user types a search. The
counts describe the device, not the current search.

`DATA_BLOCKED` is a separate filter rather than a fourth activity state,
because background data is an independent lever: an app can be Unrestricted for
background activity and still have its background data blocked.

## Section B — runtime per app

A new `usage` package with a `UsageSource` interface, a real implementation over
`UsageStatsManager`, and a fake for tests — the same seam pattern
`ShellBackend` already uses, for the same reason: the policy and UI layers must
stay testable without a paired device.

**Foreground time is computed from `UsageEvents`** (`ACTIVITY_RESUMED` and
`ACTIVITY_PAUSED`), not `queryAndAggregateUsageStats`. The aggregate API returns
daily buckets that extend before the window start, which would overcount every
app whenever the window is short — and "since last charge" is usually short.
Events are exact within the window at the cost of a manual resume/pause pairing,
including the unpaired-resume case where an app is still in the foreground.

**Permission.** `UsageStatsManager` needs the `GET_USAGE_STATS` appop. hiberna
grants it to itself through Shizuku:

```
cmd appops set com.jinatra.hiberna GET_USAGE_STATS allow
```

That is a shell command, so it goes through `ShellBackend` like every other
privileged call — the boot assertion's rule that nothing outside the `shell`
package touches a shell is not relaxed for this.

The grant happens **only after an explicit one-time prompt** that states what
usage access exposes: when every app on the device was opened and for how long.
That is more personal than the three settings hiberna currently changes, and
this app's own README criticises software that quietly widens its reach. A
revoke control sits alongside it and runs the matching `deny`. Declining leaves
the app fully functional with runtime shown as unavailable.

## Section C — battery per app

`dumpsys batterystats --charged`, parsed by a pure function in the existing
`ShellOutputParsers` style:

```kotlin
fun parseBatteryPowerUse(raw: String): BatteryPowerUse?
```

It returns **null on any unrecognised input** — never a zero, never a partial
figure. Per-uid mAh comes from the *Estimated power use* section and is
expressed as a percentage of the parsed total.

Three limits, each surfaced in the UI rather than hidden:

- **These are Android's own estimates** and may disagree with what Settings
  shows. The detail sheet says so.
- **`batterystats` attributes power to uids, not packages.** Where several
  packages share a uid, one number covers all of them, and those rows say so
  instead of implying the figure belongs to that package alone.
- **The format is undocumented.** The parser is version-guarded on the section
  header, fixtures are captured from the Pixel 10 Pro verification device into
  `app/src/test/resources/fixtures/`, and any miss degrades to "unavailable".

Containment is the whole design here: one pure function, one fixture set, one
null return. A format change costs a new fixture and a parser fix, and until
then the app shows "unavailable" rather than a wrong number.

## Section D — time window

Both metrics cover **since last full charge**, so the two numbers on a row are
comparable. The window comes from `batterystats`' own *Time on battery*.

If that parse fails, runtime does **not** disappear with it: it falls back to a
24-hour window, labelled as such. Coupling the availability of the reliable
metric to the fragile parser would be the wrong trade.

The UI states the window and how long ago the charge was. Without that, every
number looks reassuringly small ten minutes after unplugging.

## Section E — presentation

**Row** — one compact line beneath the package name: `12% · 3h 20m`. Each half
degrades independently, so `battery unavailable · 3h 20m` is a normal state, not
an error.

**Detail sheet** — the window and how long ago that charge was, mAh alongside
the percentage, foreground time, last used, and the shared-uid caveat when it
applies.

## Section F — sort

`SortBy`, one of `SEVERITY` (default), `BATTERY_DESC`, `RUNTIME_DESC`.

Severity stays the default: `reproject()`'s existing comment records that the
severity-first order is what answers "where do I start", and nothing should
regress for someone who relies on it. Rows with unavailable data sort last,
never as zero — a broken parser must not present every app as the cleanest one
on the device.

## Section G — tests

- Parser unit tests against real-device fixtures, per the existing convention
  in `app/src/test/resources/fixtures/` (marked `-text` in `.gitattributes`
  because they are byte-exact evidence).
- Parser tests for the failure path: truncated output, a missing section header,
  and vendor noise all return null.
- `FakeUsageSource` for the runtime path, including the unpaired-resume case.
- View-model tests for filter projection, chip counts and each sort order,
  including unavailable-sorts-last.
- A Robolectric render test for the row in its unavailable state.

## Out of scope

Historical graphs, wakelock and alarm counts, per-app network bytes, drain
notifications, and screenshots. No CI, signing or distribution changes.

## Known dependency

The battery parser needs a real `dumpsys batterystats --charged` capture from
the Pixel 10 Pro before it can be written against anything. It cannot be
invented: guessing at the format is exactly how this parser silently produces
wrong numbers instead of "unavailable".
