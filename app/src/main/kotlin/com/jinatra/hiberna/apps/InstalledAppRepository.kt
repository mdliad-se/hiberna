// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface InstalledAppRepository {
    suspend fun load(): List<InstalledApp>

    /** Resolved at apply time, never stored — uids are reassigned on reinstall. */
    suspend fun uidOf(packageName: String): Int?
}

class PackageManagerAppRepository(private val context: Context) : InstalledAppRepository {

    private val pm: PackageManager get() = context.packageManager

    override suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            // A disabled app cannot run in the foreground or the background,
            // so surfacing it here would be a dead entry: toggling its
            // background restriction has no observable effect. Policy is
            // still keyed by package name, so nothing is lost — the app
            // reappears the moment the user re-enables it, and uidOf still
            // resolves it on demand in the meantime.
            .filter { it.enabled }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    uid = info.uid,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun uidOf(packageName: String): Int? = withContext(Dispatchers.IO) {
        runCatching { pm.getApplicationInfo(packageName, 0).uid }.getOrNull()
    }
}

class FakeAppRepository(private val apps: List<InstalledApp>) : InstalledAppRepository {
    override suspend fun load(): List<InstalledApp> = apps
    override suspend fun uidOf(packageName: String): Int? =
        apps.firstOrNull { it.packageName == packageName }?.uid
}
