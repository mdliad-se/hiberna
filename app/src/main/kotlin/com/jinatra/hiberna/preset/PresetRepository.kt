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
 * `save`/`delete` are both read-modify-write over that one blob. `DataStore.edit`
 * serialises every call *made against this one [dataStore] instance* through a
 * single writer and always hands the transform the current on-disk value, so
 * two concurrent calls through the same instance apply in some order rather
 * than one clobbering the other's read (see `PresetRepositoryTest`'s
 * "concurrent saves" case). That guarantee does **not** extend across two
 * separate `DataStore<Preferences>` objects opened over the same file - those
 * have no mutual exclusion between them and can lose writes. Callers must
 * construct this with the shared `Context.hibernaDataStore` delegate (see
 * `HibernaDataStore.kt`) rather than a second `PreferenceDataStoreFactory.create(...)`
 * over the same file.
 *
 * A store that has never been written to reads as [DEFAULT_PRESETS], not an
 * empty list - see [presets]' and [mutate]'s own docs for exactly how that is
 * told apart from a deliberate delete-to-empty, which stays empty.
 */
class DataStorePresetRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : PresetRepository {

    // `raw == null` means the key was never written - a fresh install, or
    // one where every default was deleted and nothing has been saved since
    // (delete always writes an empty array back, see `mutate`, so it leaves
    // the key present). Only the former should see `DEFAULT_PRESETS`: a user
    // who deliberately emptied their list must see it stay empty, not have
    // it silently reseed on the next read - that would make delete look
    // broken. A stored-but-corrupt blob is not "never stored" either, so it
    // still falls through to `decode`'s existing empty-on-corruption path.
    override val presets: Flow<List<Preset>> =
        dataStore.data.map { prefs ->
            val raw = prefs[PRESETS_KEY]
            if (raw == null) DEFAULT_PRESETS else decode(raw)
        }

    /**
     * True while the *currently* stored blob is unreadable. Reflects only the
     * live state of [PRESETS_KEY] - not whether a corrupt-bytes backup exists
     * from some past event - so this clears back to `false` the moment a
     * subsequent `save`/`delete` writes a valid list, rather than latching
     * permanently true. Deliberately not part of [PresetRepository] - only
     * `presets`/`save`/`delete` are pinned by the brief - so this is a side
     * channel a caller (e.g. a settings screen) can use to tell the user
     * "your saved presets were unreadable" instead of the loss passing in
     * silence, without changing the pinned interface's shape.
     */
    val corruptionDetected: Flow<Boolean> =
        dataStore.data.map { prefs -> isUnparseable(prefs[PRESETS_KEY]) }

    override suspend fun save(preset: Preset) = mutate { current ->
        current.filterNot { it.id == preset.id } + preset
    }

    override suspend fun delete(id: String) = mutate { current ->
        current.filterNot { it.id == id }
    }

    /** Single source of truth for parsing the stored blob; used by [decode],
     * [isUnparseable] and [mutate] so the three cannot drift apart. `null`
     * means nothing was stored yet; a `Result.failure` means it was stored
     * but is not valid [Preset] JSON. */
    private fun decodeResult(raw: String?): Result<List<Preset>>? =
        raw?.let { runCatching { json.decodeFromString<List<Preset>>(it) } }

    private fun decode(raw: String?): List<Preset> =
        decodeResult(raw)?.getOrDefault(emptyList()) ?: emptyList()

    private fun isUnparseable(raw: String?): Boolean =
        decodeResult(raw)?.isFailure ?: false

    private suspend fun mutate(block: (List<Preset>) -> List<Preset>) {
        dataStore.edit { prefs ->
            val raw = prefs[PRESETS_KEY]
            val parsed = decodeResult(raw)
            if (parsed != null && parsed.isFailure) {
                // About to overwrite bytes we can't read back - keep them
                // instead of discarding the user's saved work with no trace.
                prefs[PRESETS_CORRUPT_BACKUP_KEY] = raw!!
            } else {
                // This write's starting point was healthy (or empty) - any
                // backup parked by a past corruption is stale now that the
                // user has moved on with valid data again.
                prefs.remove(PRESETS_CORRUPT_BACKUP_KEY)
            }
            // Mirrors `presets`' own seeding: a `save`/`delete` against a
            // never-touched store (`raw == null`) must act on the same
            // `DEFAULT_PRESETS` the flow shows, not on an empty list - one of
            // those virtual defaults could otherwise not be deleted first,
            // since deleting it from a never-written empty baseline would
            // just re-write an empty array and silently drop the other two,
            // which only ever existed virtually. A stored-but-corrupt blob is
            // not "never written" - `parsed.isFailure` above already parks
            // its bytes as a backup and starts this write from empty.
            val current = if (raw == null) DEFAULT_PRESETS else (parsed?.getOrNull() ?: emptyList())
            prefs[PRESETS_KEY] = json.encodeToString(block(current))
        }
    }
}
