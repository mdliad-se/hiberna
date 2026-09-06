// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.preset

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Packages where the user has overruled the guardrail. Persisted so the
 * decision is not re-litigated on every launch.
 */
interface OverrideRepository {
    val overridden: Flow<Set<String>>
    suspend fun setOverridden(packageName: String, overridden: Boolean)
}

private val OVERRIDES_KEY = stringSetPreferencesKey("sensitivity_overrides")

class DataStoreOverrideRepository(
    private val dataStore: DataStore<Preferences>,
) : OverrideRepository {

    override val overridden: Flow<Set<String>> =
        dataStore.data.map { it[OVERRIDES_KEY] ?: emptySet() }

    override suspend fun setOverridden(packageName: String, overridden: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[OVERRIDES_KEY] ?: emptySet()
            prefs[OVERRIDES_KEY] =
                if (overridden) current + packageName else current - packageName
        }
    }
}
