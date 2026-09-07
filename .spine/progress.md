# hiberna v1 progress ledger

Task 0: complete (commits b071068..fb3d8d3, review clean after one fix round)
        Gradle 8.13 wrapper bootstrapped, JBR 21 pinned, SDK path fixed.
        Review caught a Critical Properties-escaping bug originating in the
        plan itself; plan, brief and disk now agree and are committed.
Task 1: COMPLETE - privilege spike, verdict GO. Run by controller on emulator
        (AVD Medium_Phone_API_36.1, API 36), not by a subagent, because it
        needs a device. Fixtures captured, emulator state reverted.
        Not reviewed by a task reviewer - it produces fixtures, not code.
        Committed as c403fd1.
RESOLVED: the three parsers the spike disproved are fixed in the plan
        (commit c403fd1), with device-accurate tests including the two empty
        representations of appops output. See
        docs/spine/SPIKE-2026-09-02-privilege.md.
Task 2: complete (commits c753564..8d19fc5, review clean after one fix round)
        Gradle project, ShellBackend seam, FakeShellBackend, boot assertion,
        7/7 tests green, assembleDebug + assembleRelease succeed.
        Review removed a foojay JDK-download resolver (bad for F-Droid
        reproducible builds) in favour of toolchain 21 / target 17.
        Carried forward: MainActivity and launcher resources are placeholders
        for Task 10 and pre-release; the boot assertion is correct but
        decorative until a task introduces backend selection.
Task 3: complete (commits 05d05ee..79db6be, three fix rounds, both
        verdicts PASS on the final pass). 45 tests green.
        Shizuku platform, privilege gate, real shell backend. 44 tests green.
        The first review found the layer NON-FUNCTIONAL in production despite
        passing tests: FakeShizukuPlatform shipped in src/main defeating the
        boot assertion; SHIZUKU_ABSENT unreachable; no binder listeners so
        nothing ever reached READY; process leak; stream-drain deadlock; IPC
        on the main thread. Seam was redesigned under controller authority:
        ShizukuPlatform gained isInstalled + listener registration, gate
        gained CHECKING + suspend refresh + Mutex.
        Follow-ups deliberately deferred, NOT forgotten:
          - exec's drain/timeout/destroy logic has no JVM regression guard;
            a process abstraction would fix that. Device pass covers it once.
          - verifyShizukuSeeds only hooks assembleRelease/bundleRelease.
          - R8 shakes the layer out of the release APK while MainActivity is
            empty; re-check the boot assertion after Task 10.
          - NOTHING IN THIS LAYER HAS RUN ON A DEVICE.
Task 4: complete (commits 7b5a265..30192b5, one fix round, both verdicts
        PASS). 64 tests green. Parsers for all three lever formats plus
        PolicyReader. Review closed a second instance of the silent-empty bug:
        deviceidle's never-empty invariant is now enforced at runtime, so a
        0-exit empty whitelist fails the read instead of reporting "nothing
        restricted". Implementer also found a real bug in the brief's
        substringAfter(missingDelimiterValue) usage.
        Residual (accepted): a single malformed deviceidle row is dropped
        silently; only a wholly empty parse is caught.
Task 5: complete (commits 7520b1b..4efbe68, one fix round, both verdicts
        PASS). 68 tests green. InstalledAppRepository over PackageManager.
        Review reversed an implementer decision to hide disabled apps: the
        repository reports the truth, visibility is a UI concern (this codebase
        already filters system apps in the view model). InstalledApp.isEnabled
        added instead.
        CARRY INTO TASK 6: InstalledApp.uid is an enumeration-time snapshot and
        goes stale on uninstall/reinstall. It is documented, NOT enforced. Task 6
        must call uidOf(packageName) at apply time and prove it with a test.
        Reviewer's stronger options if that proves fragile: rename the field to
        read as dangerous, wrap it in a value class, or remove it entirely so
        uidOf is the only path.
Task 6: complete (commit 941db1b, NO fix round - spec PASS, quality Good on
        the first review). 78 tests green. PolicyApplier: tri-state to commands,
        uid re-resolved at apply time, halt-on-first-failure, lever named in
        every failure. Implementer added ApplyResult.Failed.applied so a caller
        can see which levers landed before a mid-sequence failure (no rollback
        by design).
        MINORS for the final whole-branch review to triage:
          - the uid-freshness test only proves no capture at CONSTRUCTION, which
            is structurally impossible anyway; nothing tests two applies on one
            instance with the resolver's answer changed between them. Today's
            class is stateless so it is correct, but a future memoizing refactor
            would not be caught.
          - ApplyResult.Failed.applied is defaulted to emptyList() with no real
            call site using the 2-arg form; a caller that forgets the argument
            gets a confidently wrong "nothing landed" instead of a compile error.
          - a not-installed package fails the WHOLE apply on the data lever even
            when the caller only changed background activity and the data lever
            was a no-op. Inherited from the brief, not an implementer deviation.
Task 7: complete (commits 4d909cd..7a32a71, one fix round, both verdicts
        PASS). 89 tests green. Guardrail detects apps that break when
        restricted, via default-SMS, default-dialer, assistant, launcher,
        SET_ALARM, AccountManager and per-package foreground-service types.
        The brief's RoleManager code did not compile - getRoleHolders is
        @SystemApi, absent from the public android.jar (verified with javap).
        Plan has a CORRECTED note recording the public substitutes.
        com.android.alarmclock proved dead on the API 36 emulator; the real
        AOSP package is com.android.deskclock.
        *** PRECONDITIONS FOR TASK 12 - do not let these ride in silently ***
          1. hasQualifyingForegroundServiceType does a per-package
             getPackageInfo(GET_SERVICES) Binder call inside classify(). Task 12
             calls classify for hundreds of packages, so that is hundreds of
             IPCs. Batch it into one getInstalledPackages(GET_SERVICES) cached
             like the other by-lazy sources BEFORE the bulk path ships.
          2. The only coverage for the FGS logic reflectively sets a private
             ServiceInfo field and silently returns with zero assertions if that
             reflection ever breaks - a test that can stop testing while still
             reporting green. Extract the bitmask decision into a pure function
             tested with plain Int literals.
Task 8: complete (commits 9ee99a6..51a77aa, one fix round, both verdicts
        PASS). 99 tests green. Presets + guardrail overrides in DataStore as a
        single JSON blob (v2 exports these exact bytes). Review caught a
        corruptionDetected flag that latched permanently true once wired to UI,
        and a concurrency claim that held only within one DataStore instance -
        now enforced by a single canonical preferencesDataStore delegate in
        HibernaDataStore.kt, which Task 14's AppContainer must inject.
        v2 precondition recorded: per-element decode tolerance, so one bad entry
        in an imported file does not discard the whole list.

=== DOMAIN LAYER COMPLETE (Tasks 0-8). UI BLOCK NEXT. ===
Note for Tasks 9-13: the plan says connectedDebugAndroidTest, which needs a
running emulator per task. Compose tests run under Robolectric on the JVM
instead (unitTests.isIncludeAndroidResources is already true), so the suite
stays device-free. Only Task 14's smoke test genuinely needs hardware.
Task 9: complete (commits 6abdbc2..511a62d). 109 tests green, still all JVM.
        Brand tokens verified byte-for-byte against ../brand/. Shadow is a
        drawn offset rect, never Compose elevation; press translates by exactly
        the offset and collapses it; disabled loses the shadow entirely.
        Review found all six tests would pass if brutalSurface drew NOTHING -
        closed with pixel-sampling tests that were watched failing when
        drawBehind was deleted.
        Accepted without a third review round: the finding was a test gap, the
        fix is a test, and its bite was demonstrated. Controller decision.
        Residual: captureToImage() hangs under Robolectric 4.16.1 (three configs
        tried), so pixels are sampled via software Canvas.draw rather than the
        hardware PixelCopy path.
        LocalReducedMotion was dropped deliberately - the press is instant, not
        animated, so nothing exists to gate. Re-add it the moment any task
        introduces a real transition, or Brand v1.1's motion rule is violated.
Task 10: complete (commits b87f778..1bb5150, one fix round, both verdicts
        PASS). 120 tests green. Gate screen + MainActivity.
        Review caught three real gaps: SHIZUKU_ABSENT was a dead end (no way to
        GET Shizuku - now an ACTION_VIEW intent to the F-Droid listing, which
        needs no permission because the browser does the networking); both CTAs
        used Product, violating the token doc's own "never replaces Teal for
        primary actions"; and MainActivity had zero coverage on the app's most
        common flow (leave, start Shizuku, come back).
        HibernaApp.container's setter is now internal for test substitution -
        verified this does NOT weaken the release boot assertion, which runs
        before assignment and is independent of setter visibility.
Task 11: complete (commits 5110054..4f34ef9, two fix rounds). 139 tests green.
        The app list: search, system filter, sensitivity flags, and an inline
        tri-state activity picker.
        The brief was wrong in three ways the implementer caught: its sample
        test contradicted itself on the showSystem default; onActivityChange was
        a DEAD PARAMETER, so every row would have been read-only in the app
        whose whole purpose is editing them; and it reused Signal for per-row
        flags, which multiplies a colour the brand reserves for one highlight
        per screen.
        Review then caught the Signal->Mist swap colliding with Mist system-row
        backgrounds (zero contrast on the only warning a user gets), a partial
        re-verification failure reported as success, raw shell stderr and
        package names in user-facing copy, an unstyled Material3 search field,
        and a picker layout that force-wrapped labels mid-word at 360dp -
        measured at 57px/three lines, now 14px/one line.
        Brand's slab-inside-a-slab rule supplied the fix: picker buttons drop
        their shadow, so the 16dp shadow-separation rule stops applying.
        NOTE: legacy Robolectric graphics mode does not measure real font
        metrics - layout tests need @GraphicsMode(NATIVE) or they pass falsely.
Task 12: complete (commits 24529e6..c6c5431, one fix round). 168 tests green.
        Multi-select, bulk apply, guardrail enforcement.
        The first review returned spec FAIL, and it was the best catch of the
        project: bulk apply was fully implemented and tested but UNREACHABLE -
        no selection affordance on rows, BulkBar never rendered. The app's
        headline capability was dead code. Now wired: long-press selects, tap
        toggles in selection mode, BulkBar appears with the selection.
        Also fixed: the guardrail PREVIEW had no source and would have drifted
        from the apply-time decision (now one shared predicate); per-app partial
        success was discarded so the summary called a partly-applied app an
        outright failure; and the honesty-rule test could not fail - its fixture
        made pre- and post-apply state identical, so deleting reflectBulk left
        it green.
        Preconditions from Task 7 discharged: the foreground-service query is
        batched into one getInstalledPackages call, and the bitmask decision is
        a pure function tested with Int literals.
        Accepted residual: no Binder-call-counter guard on the batching (no
        mocking library; PackageManager is too large to hand-subclass), so a
        regression to per-package IPC would be silent.
        Accepted without a third review round on the fixer's evidence: it
        verified the honesty test fails when reflectBulk is deleted.
Task 13: complete (commits 7498707..77dc297, one fix round). 190 tests green.
        Detail sheet + preset management. ActivityPicker extracted so the row
        and the sheet share one tri-state control instead of two that can drift.
        Review caught that DEFAULT_PRESETS was referenced NOWHERE - a fresh
        install had zero presets, so bulk apply offered an empty list to every
        new user, and deleting all presets was a one-way door. Now: an absent
        key seeds the defaults, a deliberately-emptied list stays empty, and
        PresetScreen offers "Restore default presets" when empty.
        Scoped out deliberately, recorded not forgotten: creating presets and
        editing their activity/data values. DEFAULT_PRESETS is all a user gets.
        Follow-ups recorded: a BrutalSwitch (the stock Material3 Switch is
        rounded, against the radius-0 rule - it came from the brief), and a
        cooldown between the two taps of arm/confirm delete.
        NOTE: the implementer did not commit; the controller committed for it.
*** TASK 14 MUST WIRE AppDetailSheet AND PresetScreen ***
        Both are complete and tested but unreachable, deliberately deferred to
        Task 14. Task 12 shipped exactly this way and the review called it spec
        FAIL. Do not repeat that: navigation, AppContainer repositories, and
        MainActivity.openAppSettings(packageName) all belong to Task 14.
Task 14: complete (commit 16ce760). 197 tests green, suite terminates in 37s.
        AppDetailSheet, PresetScreen and bulk selection are now REACHABLE from
        MainActivity - the gap that made Task 12 fail review. Hand-rolled nav
        (sealed Nav + BackHandler, no navigation library), detail sheet presents
        over the list, back returns to the list instead of exiting. Both
        repositories share the one canonical DataStore instance.
        Two things cost most of this task, both worth recording:
          1. TWO GRADLE DAEMONS RAN THE SAME TEST TASK AT ONCE (the subagent's
             and the controller's), producing "Unable to delete directory
             test-results/binary" and a batch of PHANTOM failures that looked
             like real regressions. Never drive Gradle while a subagent is.
          2. MainActivityReadyWiringTest HUNG THE WHOLE SUITE indefinitely. It
             polled compose.onAllNodesWithText in a hand-rolled loop against the
             real container; that call blocks inside Compose's idling check, so
             the loop's own 5s deadline never fired. Replaced by
             ReadyScreenWiringTest, which proves the same reachability against
             fakes with createAndroidComposeRule. A hanging test is worse than
             no test - anything that waits must fail, not hang.
        Dropped and recorded, not silently cut: a case covering
        MainActivity.openAppSettings firing the real Android intent.

=== ALL 15 TASKS COMPLETE. 197 tests. Next: final whole-branch review. ===

=== FINAL WHOLE-BRANCH REVIEW ===
First pass: ready-with-fixes. Three merge-blockers, each spanning a seam no
single-task review could see:
  F1 bulkSummary was computed, tested, and NEVER DISPLAYED - a bulk apply
     reported nothing, so skipped and failed apps were silent. The same
     "implemented, tested, unreachable" defect that made Task 12 fail review,
     repeated one layer up: one brief created the field, another wired screens
     rather than screen parameters, and neither diff showed both halves.
  F2 the guardrail PREVIEW could contradict the button next to it - one scalar
     count computed against presets.firstOrNull() while BulkBar renders a
     button per preset, and mutate appended on edit so the previewed preset
     silently changed. Shared predicate, wrong argument.
  F3 gradle.properties pinned a machine-local JDK path, so nobody but the
     author could build - and F-Droid builds from source.
Plus: 21 files missing SPDX headers; the canvas was white not Cream and the
launcher icon was still a Task-2 placeholder; and the guardrail FAILED OPEN -
a throwing detection source returned empty, classifying every app NONE, so a
bulk apply would have restricted the launcher, dialer and SMS app with no skip
and no error. That is the exact failure PolicyReader was hardened against
twice; the guardrail was the one place the lesson had not been applied.
All six fixed (a66139d). Second pass: READY. 204 tests, release APK builds.
README corrected (6cf50f5) - it promised JDK 17 while the build needs 21.
Gate D review receipt recorded at 6cf50f5.

=== SHIPPED AS RECORDED GAPS (not blocking) ===
  - UNKNOWN-sensitivity apps are skipped by the guardrail but the detail sheet
    offers no override control, and the summary copy misdescribes why.
  - reflectBulk's null-uid path lacks the error the single-edit path raises.
  - showSystem is plumbed through the view model with no UI affordance.
  - Fake* classes ship in src/main (the rule was applied once, to
    FakeShizukuPlatform, and never generalised).
  - ManifestPermissionsTest asserts INTERNET-absent and QUERY_ALL_PACKAGES-
    present, not "exactly these".
  - corruptionDetected has no consumer.
  - SensitivityDetector's by-lazy sources never re-evaluate, and F6 raised the
    blast radius: one transient throw makes every app UNKNOWN until restart.
  - No JVM regression guard on exec's drain/timeout/destroy, nor on the
    batched foreground-service query.
  - Preset creation and value-editing are not implemented.
  - Stock rounded Material3 Switches violate the radius-0 rule (no BrutalSwitch).

=== THE LARGEST REMAINING RISK ===
Nothing in the privilege layer has EVER run on hardware. RealShizukuPlatform
.exec - the concurrent drains, the 10s waitForTimeout, the stuck-drain
downgrade, destroy()-in-finally - is invoked by no test in this branch.
ShizukuSmokeTest would run it but assumeTrue's on Shizuku availability, so it
has only ever SKIPPED. The ~30 lines carrying all of this app's actual power
have never executed anywhere. 204 green tests justify confidence in the
parsers, the policy state machine, the guardrail predicate and the persistence
layer - and in the fact that a silently broken privilege layer surfaces as a
visible error rather than "nothing restricted". They justify nothing about the
seam itself. The first device run is a FIRST run, not a confirmation.
