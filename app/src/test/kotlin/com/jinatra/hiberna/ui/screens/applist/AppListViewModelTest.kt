// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jinatra.hiberna.apps.FakeAppRepository
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.guardrail.FakeSensitivityDetector
import com.jinatra.hiberna.metrics.FakeUsageSource
import com.jinatra.hiberna.metrics.MetricsReader
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.policy.BulkApplier
import com.jinatra.hiberna.policy.PolicyApplier
import com.jinatra.hiberna.policy.PolicyReader
import com.jinatra.hiberna.preset.DataStoreOverrideRepository
import com.jinatra.hiberna.preset.OverrideRepository
import com.jinatra.hiberna.preset.Preset
import com.jinatra.hiberna.severity.Severity
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellBackend
import com.jinatra.hiberna.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppListViewModelTest {

    @get:Rule val tmp = TemporaryFolder()
    private var storeCounter = 0

    /** A fresh, isolated DataStore file per call - same pattern as PresetRepositoryTest. */
    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "overrides-${storeCounter++}.preferences_pb") },
        )

    private val userApp = InstalledApp("com.example.game", "Game", 10456, isSystem = false, isEnabled = true)
    private val systemApp = InstalledApp("com.android.systemui", "System UI", 10023, isSystem = true, isEnabled = true)
    private val smsApp = InstalledApp("com.example.sms", "Messages", 10500, isSystem = false, isEnabled = true)

    /**
     * Counts calls to [InstalledAppRepository.load] so a test can assert that
     * a single-row toggle does not re-trigger a full `PackageManager`
     * enumeration - see judgment call (a) in the task report.
     */
    private class CountingAppRepository(private val delegate: InstalledAppRepository) : InstalledAppRepository {
        var loadCalls = 0
            private set

        override suspend fun load(): List<InstalledApp> {
            loadCalls++
            return delegate.load()
        }

        override suspend fun uidOf(packageName: String): Int? = delegate.uidOf(packageName)
    }

    /**
     * Simulates a uid lookup that succeeds the first time (the write inside
     * [PolicyApplier.apply]) but fails the second time (the re-verification
     * inside `applyAndReflect`) for one specific package - the scenario F2
     * covers: the apply itself lands, but re-confirming the data lever
     * afterward cannot resolve a uid.
     */
    private class FlakyUidRepository(
        private val delegate: InstalledAppRepository,
        private val failOnCallNumber: Int,
        private val targetPackage: String,
    ) : InstalledAppRepository {
        private var calls = 0

        override suspend fun load(): List<InstalledApp> = delegate.load()

        override suspend fun uidOf(packageName: String): Int? {
            if (packageName != targetPackage) return delegate.uidOf(packageName)
            calls++
            return if (calls == failOnCallNumber) null else delegate.uidOf(packageName)
        }
    }

    /**
     * M1/M2: a [ShellBackend] whose `dumpsys deviceidle whitelist` READ
     * response (no sign) can disagree with what a naive "trust the request"
     * design would show - it starts by reporting [targetPackage] NOT
     * whitelisted, then flips to reporting it whitelisted only once the
     * corresponding WRITE (`... whitelist +targetPackage`) has actually
     * executed against [delegate] (M1's real-partial-success scenario), or
     * unconditionally from construction (M2's write-succeeds-but-read-back-
     * disagrees scenario, via [alwaysReportWhitelisted]) - simulating a
     * device where the write landed but the system does not end up matching
     * what was asked for. Every other command is passed straight through to
     * [delegate] unchanged.
     */
    private class DisagreeingWhitelistShellBackend(
        private val delegate: FakeShellBackend,
        private val targetPackage: String,
        private val alwaysReportWhitelisted: Boolean = false,
    ) : ShellBackend {
        override val isAvailable: Boolean = true
        private var whitelistWriteLanded = false

        override suspend fun exec(command: List<String>): ShellResult {
            val joined = command.joinToString(" ")
            if (joined == "dumpsys deviceidle whitelist +$targetPackage") {
                whitelistWriteLanded = true
                return delegate.exec(command)
            }
            if (joined == "dumpsys deviceidle whitelist") {
                val reportWhitelisted = alwaysReportWhitelisted || whitelistWriteLanded
                return if (reportWhitelisted) {
                    ShellResult(0, "user,com.example.sms,10500\nuser,$targetPackage,10456", "")
                } else {
                    ShellResult(0, "user,com.example.sms,10500", "")
                }
            }
            return delegate.exec(command)
        }
    }

    private fun shell() = FakeShellBackend().apply {
        script("appops query-op", ShellResult(0, "com.example.game", ""))
        script("deviceidle whitelist", ShellResult(0, "user,com.example.sms,10500", ""))
        script("netpolicy list", ShellResult(0, "10456", ""))
        script("appops set", ShellResult(0, "", ""))
        script("deviceidle whitelist -", ShellResult(0, "", ""))
        script("netpolicy", ShellResult(0, "", ""))
    }

    private fun vm(
        shell: ShellBackend = shell(),
        apps: InstalledAppRepository = FakeAppRepository(listOf(userApp, systemApp, smsApp)),
        overrides: OverrideRepository = DataStoreOverrideRepository(store()),
    ): AppListViewModel {
        return AppListViewModel(
            apps = apps,
            reader = PolicyReader(shell),
            applier = PolicyApplier(shell, apps),
            sensitivity = FakeSensitivityDetector(setOf("com.example.sms")),
            bulk = BulkApplier(PolicyApplier(shell, apps)),
            overrides = overrides,
            metrics = metricsReader(),
        )
    }

    private val frugal = Preset("frugal", "Frugal", BackgroundActivity.RESTRICTED, restrictBackgroundData = false)

    @Test
    fun `maps system state onto each row`() = runTest {
        val model = vm()
        model.load()
        // System apps are hidden by default (see "hides system apps until
        // asked" below); this test is about the mapping, not the filter, and
        // needs the system row visible to assert on it.
        model.onShowSystemChange(true)

        val rows = model.state.value.rows.associateBy { it.app.packageName }
        assertEquals(BackgroundActivity.RESTRICTED, rows.getValue("com.example.game").activity)
        assertEquals(BackgroundActivity.UNRESTRICTED, rows.getValue("com.example.sms").activity)
        assertEquals(BackgroundActivity.OPTIMIZED, rows.getValue("com.android.systemui").activity)
        assertTrue(rows.getValue("com.example.game").dataBlocked)
    }

    @Test
    fun `hides system apps until asked`() = runTest {
        val model = vm()
        model.load()

        assertTrue(model.state.value.rows.none { it.app.isSystem })

        model.onShowSystemChange(true)
        assertTrue(model.state.value.rows.any { it.app.isSystem })
    }

    @Test
    fun `search matches label case-insensitively`() = runTest {
        val model = vm()
        model.load()

        model.onQueryChange("gAmE")

        assertEquals(listOf("com.example.game"), model.state.value.rows.map { it.app.packageName })
    }

    @Test
    fun `flags sensitive apps`() = runTest {
        val model = vm()
        model.load()
        model.onShowSystemChange(true)

        val sms = model.state.value.rows.first { it.app.packageName == "com.example.sms" }
        assertEquals(Sensitivity.LIKELY_BREAKS, sms.sensitivity)
    }

    @Test
    fun `a failed read surfaces an error instead of an empty list`() = runTest {
        val broken = FakeShellBackend().apply {
            script("appops query-op", ShellResult(1, "", "denied"))
        }
        val model = vm(broken)

        model.load()

        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.rows.isEmpty())
    }

    @Test
    fun `a failed read preserves the user's search and filter instead of resetting them`() = runTest {
        val model = vm()
        model.load()
        model.onQueryChange("game")
        model.onShowSystemChange(true)

        val broken = FakeShellBackend().apply {
            script("appops query-op", ShellResult(1, "", "denied"))
        }
        val model2 = vm(broken)
        model2.onQueryChange("game")
        model2.onShowSystemChange(true)

        model2.load()

        assertNotNull(model2.state.value.error)
        assertEquals("game", model2.state.value.query)
        assertTrue(model2.state.value.showSystem)
    }

    @Test
    fun `a successful apply re-confirms via the system rather than trusting the applied values`() = runTest {
        // The fake shell's scripted responses never actually mutate when a
        // write runs, so a design that trusted the write outright would show
        // UNRESTRICTED here. Re-reading instead surfaces the real (unchanged)
        // state - exactly like a device where the writes landed but the
        // system did not end up matching what was asked for.
        val shell = shell()
        val model = vm(shell)
        model.load()

        model.setActivity("com.example.game", BackgroundActivity.UNRESTRICTED)

        val row = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        assertEquals(BackgroundActivity.RESTRICTED, row.activity)
        assertNull(model.state.value.error)

        val appOpsReads = shell.executed.count { it.joinToString(" ").contains("appops query-op") }
        assertEquals(2, appOpsReads)
    }

    @Test
    fun `M2 - a row's severity tier follows the read-back state, never the request, even when the write reports success`() = runTest {
        // The central invariant this whole app rests on ("a tier comes from
        // read-back state, never from the request") had no automated test
        // before this - only a manual device run. This scripts the exact
        // scenario that would catch an optimistic-UI regression: the write
        // reports ApplyResult.Success, but the fake shell's scripted read
        // responses never actually mutate, so the read-back disagrees with
        // what was requested. If a future change computed Severity from
        // `desired` (the request) instead of the re-read `confirmed` row,
        // this would be the only test in the suite to notice.
        val shell = shell()
        val model = vm(shell)
        model.load()

        val before = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        // Not sensitive (FakeSensitivityDetector only flags com.example.sms),
        // not system, currently RESTRICTED (per shell()'s scripted appops
        // read) - SAFE, not yet RECOMMENDED.
        assertEquals(Severity.SAFE, before.severity)

        model.setActivity("com.example.game", BackgroundActivity.UNRESTRICTED)

        val row = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        // Requesting UNRESTRICTED, if trusted outright, would earn
        // RECOMMENDED (non-sensitive, non-system, "unrestricted"). The real
        // read-back never changes (the fake shell's responses are static),
        // so the confirmed activity is still not UNRESTRICTED and the tier
        // must follow that read, not the request.
        assertEquals(BackgroundActivity.RESTRICTED, row.activity)
        assertEquals(Severity.SAFE, row.severity)
    }

    @Test
    fun `a successful apply does not re-scan every installed app`() = runTest {
        val counting = CountingAppRepository(FakeAppRepository(listOf(userApp, systemApp, smsApp)))
        val model = vm(apps = counting)
        model.load()
        assertEquals(1, counting.loadCalls)

        model.setActivity("com.example.game", BackgroundActivity.UNRESTRICTED)

        assertEquals(1, counting.loadCalls)
    }

    @Test
    fun `a failed apply leaves the row showing real state and names the levers already applied`() = runTest {
        val shell = FakeShellBackend().apply {
            // Ordered before the generic "deviceidle whitelist" read entry so
            // this more specific write match wins for the write command,
            // which also contains that shorter substring.
            script("-com.example.game", ShellResult(1, "", "permission denied"))
            script("appops query-op", ShellResult(0, "com.example.game", ""))
            script("deviceidle whitelist", ShellResult(0, "user,com.example.sms,10500", ""))
            script("netpolicy list", ShellResult(0, "10456", ""))
            script("appops set", ShellResult(0, "", ""))
            script("netpolicy", ShellResult(0, "", ""))
        }
        val model = vm(shell)
        model.load()

        // OPTIMIZED signs the battery-whitelist write with "-", matching the
        // scripted failure above; appops set (allow) still succeeds first.
        model.setActivity("com.example.game", BackgroundActivity.OPTIMIZED)

        val row = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        assertEquals(BackgroundActivity.RESTRICTED, row.activity)
        assertNotNull(model.state.value.error)
        val error = model.state.value.error!!
        assertTrue(error.contains("battery"))
        assertTrue(error.contains("appops"))
    }

    @Test
    fun `M1 - a partial write failure re-reads real state, matching reflectBulk's own rule`() = runTest {
        // The write's earlier levers (appops, battery) land for real before
        // the later data lever fails - ApplyResult.Failed("data", applied =
        // ["appops", "battery"]). Before M1, applyAndReflect's Failed branch
        // re-read nothing, so the row would keep its stale pre-apply
        // activity even though the battery lever's own applied=[...] list
        // says otherwise. DisagreeingWhitelistShellBackend flips its
        // whitelist READ only once the whitelist WRITE actually executes, so
        // a passing assertion here is only possible if applyAndReflect
        // actually re-reads after the failure, not because the fake happens
        // to already agree.
        val delegate = FakeShellBackend().apply {
            script("appops query-op", ShellResult(0, "", "")) // nothing appops-restricted
            script("deviceidle whitelist", ShellResult(0, "user,com.example.sms,10500", ""))
            script("netpolicy list", ShellResult(0, "", "")) // nothing data-blocked
            script("appops set", ShellResult(0, "", ""))
            script("deviceidle whitelist +", ShellResult(0, "", ""))
            script("netpolicy remove", ShellResult(1, "", "permission denied"))
        }
        val shell = DisagreeingWhitelistShellBackend(delegate, targetPackage = "com.example.game")
        val model = vm(shell)
        model.load()

        val before = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        assertEquals(BackgroundActivity.OPTIMIZED, before.activity)

        // UNRESTRICTED signs the battery-whitelist write with "+"; appops set
        // (allow) also succeeds; the data lever (netpolicy remove, since
        // dataBlocked stays false) is the one scripted to fail.
        model.setActivity("com.example.game", BackgroundActivity.UNRESTRICTED)

        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.error!!.contains("data"))
        val row = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        assertEquals(
            "the battery lever actually landed (see ApplyResult.Failed.applied) - a re-read " +
                "must show it, not the stale pre-apply OPTIMIZED value",
            BackgroundActivity.UNRESTRICTED,
            row.activity,
        )
    }

    @Test
    fun `a partial re-verification failure surfaces an error instead of a silently stale value`() = runTest {
        // The apply itself succeeds (ApplyResult.Success), but the uid
        // lookup used afterward to re-confirm dataBlocked fails - a
        // narrower, distinguishable failure than the whole apply failing.
        val shell = shell()
        val flaky = FlakyUidRepository(
            delegate = FakeAppRepository(listOf(userApp, systemApp, smsApp)),
            failOnCallNumber = 2,
            targetPackage = "com.example.game",
        )
        val model = vm(shell = shell, apps = flaky)
        model.load()

        model.setActivity("com.example.game", BackgroundActivity.UNRESTRICTED)

        // Success is not silently reported: the row is left showing real
        // system state (activity, confirmed via the read that did succeed)
        // but the error is non-null because the data lever's re-verification
        // could not run - never both "row updated" and "error null" at once
        // for a part of the result that was never actually confirmed.
        assertNotNull(model.state.value.error)
        val row = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        assertEquals(BackgroundActivity.RESTRICTED, row.activity)
    }

    @Test
    fun `setDataBlocked applies and reflects the real data-blocking state`() = runTest {
        // The fake shell's scripted "netpolicy list" response reports uid
        // 10456 (com.example.game) as blocked and never mutates on write, so
        // requesting `false` here while the row still confirms `true`
        // proves the value comes from the real re-read - not from
        // optimistically trusting the `false` that was just requested.
        val model = vm()
        model.load()

        model.setDataBlocked("com.example.game", false)

        val row = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        assertTrue(row.dataBlocked)
        assertNull(model.state.value.error)
    }

    // --- Task 12: multi-select and bulk apply ---

    @Test
    fun `toggleSelection adds and removes a package`() = runTest {
        val model = vm()

        model.toggleSelection("com.example.game")
        assertEquals(setOf("com.example.game"), model.selected.value)

        model.toggleSelection("com.example.sms")
        assertEquals(setOf("com.example.game", "com.example.sms"), model.selected.value)

        model.toggleSelection("com.example.game")
        assertEquals(setOf("com.example.sms"), model.selected.value)
    }

    @Test
    fun `clearSelection empties the selection`() = runTest {
        val model = vm()
        model.toggleSelection("com.example.game")

        model.clearSelection()

        assertTrue(model.selected.value.isEmpty())
    }

    @Test
    fun `applyPreset clears the selection once it has run`() = runTest {
        val shell = shell()
        val model = vm(shell)
        model.load()
        model.toggleSelection("com.example.game")

        model.applyPreset(frugal)

        assertTrue(model.selected.value.isEmpty())
        // A stub that only clears the selection (and does nothing else)
        // would also pass the assertion above; these two guard against that
        // degenerate implementation by requiring the preset to have actually
        // reached BulkApplier/PolicyApplier and produced a real outcome.
        assertTrue(shell.executed.any { it.joinToString(" ").contains("appops set") })
        assertEquals("Changed 1 app.", model.state.value.bulkSummary)
    }

    @Test
    fun `applyPreset skips a sensitive selected app by default and says so in the summary`() = runTest {
        // com.example.sms is sensitive per the shared FakeSensitivityDetector
        // wired into vm(); frugal's skipSensitive defaults to true.
        val model = vm()
        model.load()
        model.toggleSelection("com.example.game")
        model.toggleSelection("com.example.sms")

        model.applyPreset(frugal)

        val summary = model.state.value.bulkSummary
        assertNotNull(summary)
        assertTrue(summary!!.contains("1"))
        assertTrue(summary.contains("notifications") || summary.contains("alarms"))
    }

    @Test
    fun `skippedCount previews exactly what applyPreset's own outcome later reports as skipped`() = runTest {
        // F2: this is what stands guard against the preview and BulkApplier
        // drifting apart - both share BulkTarget.isSkippedByGuardrail, but a
        // future edit to either call site could still reintroduce two
        // separate copies of "is this app sensitive and not overridden"; this
        // test would then start failing the moment the numbers disagreed.
        val model = vm()
        model.load()
        model.toggleSelection("com.example.game")
        model.toggleSelection("com.example.sms")

        val overridden = emptySet<String>()
        val previewed = model.skippedCount(frugal, overridden)
        assertEquals(1, previewed)

        model.applyPreset(frugal)

        // The actual BulkOutcome the apply produced (surfaced only through
        // the summary here) skipped exactly as many as the preview promised.
        assertTrue(model.state.value.bulkSummary!!.contains("Left $previewed alone"))
    }

    @Test
    fun `an override on the sensitive package removes it from the skipped summary`() = runTest {
        val overrides = DataStoreOverrideRepository(store())
        val model = vm(overrides = overrides)
        model.load()
        model.toggleSelection("com.example.game")
        model.toggleSelection("com.example.sms")
        model.applyPreset(frugal)
        assertTrue(model.state.value.bulkSummary!!.contains("Left"))

        overrides.setOverridden("com.example.sms", true)
        model.toggleSelection("com.example.game")
        model.toggleSelection("com.example.sms")
        model.applyPreset(frugal)

        assertFalse(model.state.value.bulkSummary!!.contains("Left"))
    }

    @Test
    fun `a bulk apply re-reads real state for touched packages only, via PolicyReader not a full rescan`() = runTest {
        // F4: the pre-apply and post-apply appops reads must genuinely
        // differ, or this test cannot tell "reflectBulk re-read real state"
        // apart from "the row was just never touched after load()". The
        // fixture used to report com.example.game as restricted both at
        // load() time and again in the (identical, static) read reflectBulk
        // would perform - so deleting reflectBulk entirely left this test
        // green, since a stale cached value and a fresh re-read were
        // indistinguishable. Here the appops script is mutated between
        // load() and applyPreset() to model the on-device state genuinely
        // moving - com.example.game is no longer appops-restricted - so a
        // stale cache (RESTRICTED, left over from load()) and a fresh
        // re-read (OPTIMIZED, since the battery whitelist still does not
        // list it either) disagree, and only reflectBulk can produce the
        // latter.
        val counting = CountingAppRepository(FakeAppRepository(listOf(userApp, systemApp, smsApp)))
        val shell = shell()
        val model = vm(shell = shell, apps = counting)
        model.load()
        assertEquals(1, counting.loadCalls)

        val before = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        assertEquals(BackgroundActivity.RESTRICTED, before.activity)

        shell.script("appops query-op", ShellResult(0, "", ""))

        model.toggleSelection("com.example.game")
        model.applyPreset(frugal)

        val row = model.state.value.rows.first { it.app.packageName == "com.example.game" }
        assertEquals(BackgroundActivity.OPTIMIZED, row.activity)
        assertEquals(1, counting.loadCalls)
    }

    @Test
    fun `a failed package in a bulk apply is named by label in the summary without aborting the rest`() = runTest {
        val shell = FakeShellBackend().apply {
            // More specific match registered first - see the class doc above
            // on FakeShellBackend's first-match-wins semantics.
            script("appops set com.example.game", ShellResult(1, "", "permission denied"))
            script("appops query-op", ShellResult(0, "com.example.game", ""))
            script("deviceidle whitelist", ShellResult(0, "user,com.example.sms,10500", ""))
            script("netpolicy list", ShellResult(0, "10456", ""))
            script("appops set", ShellResult(0, "", ""))
            script("netpolicy", ShellResult(0, "", ""))
        }
        val reckless = frugal.copy(id = "reckless", skipSensitive = false)
        val model = vm(shell)
        model.load()
        model.toggleSelection("com.example.game")
        model.toggleSelection("com.example.sms")

        model.applyPreset(reckless)

        val summary = model.state.value.bulkSummary!!
        assertTrue("expected the app's label, not just its package name: $summary", summary.contains("Game"))
        assertTrue("expected the failing lever named: $summary", summary.contains("appops"))
    }

    /**
     * Metrics are advisory, and this file is not about them, so every view
     * model here gets a reader whose two sources are both absent: no
     * batterystats script on the shell, and usage access denied. That is a
     * real device state - no privilege, prompt declined - and it must leave
     * every assertion in this file untouched. The metric behaviour itself is
     * covered by MetricsReaderTest.
     */
    private fun metricsReader(): MetricsReader =
        MetricsReader(FakeShellBackend(isAvailable = false), FakeUsageSource(access = false))
}
