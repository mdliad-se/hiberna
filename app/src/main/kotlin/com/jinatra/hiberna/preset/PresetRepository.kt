// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.preset

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PresetRepository {
    val presets: Flow<List<Preset>>
    suspend fun save(preset: Preset)
    suspend fun delete(id: String)
}

private val PRESETS_KEY = stringPreferencesKey("presets_json")

// Where a stored blob that failed to parse is preserved. `presets` still has
// to report an empty list on corruption - `Flow<List<Preset>>` has no room to
// say anything else - but the raw bytes are not lost the moment the next
// save/delete lands; they are parked here instead of being overwritten with
// no trace. See `corruptionDetected` for the observable side of this.
private val PRESETS_CORRUPT_BACKUP_KEY = stringPreferencesKey("presets_json_corrupt_backup")

/**
 * JSON in a single preference rather than a table. The dataset is a handful of
 * rows, and v2's export feature is a copy of these same bytes - one
 * representation instead of a storage schema plus a wire format.
 *
 * `save`/`delete` are both read-modify-write over that one blob, but this is
 * safe under concurrency: `DataStore.edit` serialises every call through a
 * single writer and always hands the transform the current on-disk value, so
 * two concurrent calls apply in some order rather than one clobbering the
 * other's read. See `PresetRepositoryTest`'s "concurrent saves" case.
 */
class DataStorePresetRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : PresetRepository {

    override val presets: Flow<List<Preset>> =
        dataStore.data.map { prefs -> decode(prefs[PRESETS_KEY]) }

    /**
     * True once a stored blob has been found unreadable. Deliberately not
     * part of [PresetRepository] - only `presets`/`save`/`delete` are pinned
     * by the brief - so this is a side channel a caller (e.g. a settings
     * screen) can use to tell the user "your saved presets were unreadable"
     * instead of the loss passing in silence, without changing the pinned
     * interface's shape.
     */
    val corruptionDetected: Flow<Boolean> =
        dataStore.data.map { prefs ->
            isUnparseable(prefs[PRESETS_KEY]) || prefs[PRESETS_CORRUPT_BACKUP_KEY] != null
        }

    override suspend fun save(preset: Preset) = mutate { current ->
        current.filterNot { it.id == preset.id } + preset
    }

    override suspend fun delete(id: String) = mutate { current ->
        current.filterNot { it.id == id }
    }

    private fun decode(raw: String?): List<Preset> =
        raw?.let { runCatching { json.decodeFromString<List<Preset>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()

    private fun isUnparseable(raw: String?): Boolean =
        raw != null && runCatching { json.decodeFromString<List<Preset>>(raw) }.isFailure

    private suspend fun mutate(block: (List<Preset>) -> List<Preset>) {
        dataStore.edit { prefs ->
            val raw = prefs[PRESETS_KEY]
            val parsed = raw?.let { runCatching { json.decodeFromString<List<Preset>>(it) } }
            if (parsed != null && parsed.isFailure) {
                // About to overwrite bytes we can't read back - keep them
                // instead of discarding the user's saved work with no trace.
                prefs[PRESETS_CORRUPT_BACKUP_KEY] = raw!!
            }
            val current = parsed?.getOrNull() ?: emptyList()
            prefs[PRESETS_KEY] = json.encodeToString(block(current))
        }
    }
}
