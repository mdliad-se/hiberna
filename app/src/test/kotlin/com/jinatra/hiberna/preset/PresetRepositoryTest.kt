// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.preset

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jinatra.hiberna.policy.BackgroundActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PresetRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private var counter = 0

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "test-${counter++}.preferences_pb") },
        )

    @Test
    fun `saves and reads back a preset`() = runTest {
        val repo = DataStorePresetRepository(store())
        val preset = Preset("aggressive", "Aggressive", BackgroundActivity.RESTRICTED, true)

        repo.save(preset)

        assertEquals(listOf(preset), repo.presets.first())
    }

    @Test
    fun `saving the same id replaces rather than duplicates`() = runTest {
        val repo = DataStorePresetRepository(store())
        repo.save(Preset("a", "First", BackgroundActivity.RESTRICTED, true))
        repo.save(Preset("a", "Renamed", BackgroundActivity.OPTIMIZED, false))

        val presets = repo.presets.first()
        assertEquals(1, presets.size)
        assertEquals("Renamed", presets.single().name)
    }

    @Test
    fun `deletes by id`() = runTest {
        val repo = DataStorePresetRepository(store())
        repo.save(Preset("a", "First", BackgroundActivity.RESTRICTED, true))

        // Load-bearing: without this, a no-op `save` or a `presets` flow that
        // always emits empty would still pass the assertion below.
        assertEquals(1, repo.presets.first().size)

        repo.delete("a")

        assertTrue(repo.presets.first().isEmpty())
    }

    @Test
    fun `overrides round-trip`() = runTest {
        val repo = DataStoreOverrideRepository(store())

        repo.setOverridden("com.example.sms", true)
        assertEquals(setOf("com.example.sms"), repo.overridden.first())

        repo.setOverridden("com.example.sms", false)
        assertTrue(repo.overridden.first().isEmpty())
    }

    @Test
    fun `skipSensitive defaults to true so bulk is safe by default`() {
        assertTrue(Preset("x", "X", BackgroundActivity.RESTRICTED, false).skipSensitive)
        assertTrue(DEFAULT_PRESETS.all { it.skipSensitive })
    }

    // --- judgement call (a): corrupt stored JSON must not be a silent, ---
    // --- irrecoverable data loss. ---

    @Test
    fun `corrupt stored json reads back as empty but is flagged, not silently lost`() = runTest {
        val dataStore = store()
        val presetsKey = stringPreferencesKey("presets_json")
        dataStore.edit { it[presetsKey] = "{ this is not valid json" }

        val repo = DataStorePresetRepository(dataStore)

        // The pinned `Flow<List<Preset>>` contract has no room to distinguish
        // "no presets saved" from "presets saved but unreadable" - it can only
        // report an empty list either way.
        assertTrue(repo.presets.first().isEmpty())

        // But the corruption itself is not swallowed: it is observable...
        assertTrue(repo.corruptionDetected.first())
    }

    @Test
    fun `saving after corruption preserves the corrupt bytes instead of erasing them`() = runTest {
        val dataStore = store()
        val presetsKey = stringPreferencesKey("presets_json")
        val backupKey = stringPreferencesKey("presets_json_corrupt_backup")
        dataStore.edit { it[presetsKey] = "{ this is not valid json" }

        val repo = DataStorePresetRepository(dataStore)
        // A save is a read-modify-write over the whole blob; without a backup
        // this would silently overwrite the corrupt bytes with no trace they
        // ever existed.
        repo.save(Preset("a", "A", BackgroundActivity.RESTRICTED, true))

        assertEquals(listOf("A"), repo.presets.first().map { it.name })
        assertEquals("{ this is not valid json", dataStore.data.first()[backupKey])
    }

    @Test
    fun `corruptionDetected is false for a healthy store`() = runTest {
        val repo = DataStorePresetRepository(store())

        assertTrue(repo.corruptionDetected.first().not())

        repo.save(Preset("a", "First", BackgroundActivity.RESTRICTED, true))

        assertTrue(repo.corruptionDetected.first().not())
    }

    @Test
    fun `corruptionDetected returns to false after a successful save following corruption`() = runTest {
        val dataStore = store()
        val presetsKey = stringPreferencesKey("presets_json")
        dataStore.edit { it[presetsKey] = "{ this is not valid json" }

        val repo = DataStorePresetRepository(dataStore)
        assertTrue(repo.corruptionDetected.first())

        // The user has successfully saved valid presets again - the flag must
        // not latch permanently true from the earlier corruption.
        repo.save(Preset("a", "A", BackgroundActivity.RESTRICTED, true))

        assertTrue(repo.corruptionDetected.first().not())
    }

    // --- judgement call (b): concurrent read-modify-write must not lose a write. ---

    @Test
    fun `concurrent saves do not lose a write`() = runTest {
        val repo = DataStorePresetRepository(store())
        val toSave = (1..20).map { Preset(it.toString(), "P$it", BackgroundActivity.OPTIMIZED, false) }

        coroutineScope {
            toSave.forEach { preset -> launch { repo.save(preset) } }
        }

        assertEquals(toSave.map { it.id }.toSet(), repo.presets.first().map { it.id }.toSet())
    }
}
