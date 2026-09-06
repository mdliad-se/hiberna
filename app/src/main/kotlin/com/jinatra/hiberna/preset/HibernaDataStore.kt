// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.preset

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The single canonical [DataStore] for the app's "hiberna" preferences file.
 *
 * `DataStore.edit`/`updateData` only serialise writes *within one instance* -
 * two separate `DataStore<Preferences>` objects opened over the same file
 * have no mutual exclusion between them and can lose writes to each other.
 * The `by preferencesDataStore(...)` delegate below is a Kotlin property
 * evaluated once per [Context], so as long as this is the *only* declaration
 * of a `preferencesDataStore(name = "hiberna")` delegate anywhere in the app,
 * every caller reading `context.hibernaDataStore` shares that one instance -
 * that is what actually makes the concurrency safety documented on
 * [DataStorePresetRepository] and [DataStoreOverrideRepository] hold.
 *
 * Do not declare a second `preferencesDataStore(name = "hiberna")` (or a
 * `PreferenceDataStoreFactory.create(...)` over the same file) anywhere else.
 *
 * Task 14 (DI wiring / `AppContainer`) should inject this exact delegate into
 * both [DataStorePresetRepository] and [DataStoreOverrideRepository].
 */
internal val Context.hibernaDataStore: DataStore<Preferences> by preferencesDataStore(name = "hiberna")
