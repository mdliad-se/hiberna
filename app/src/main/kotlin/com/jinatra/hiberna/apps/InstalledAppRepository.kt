// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

interface InstalledAppRepository {
    /**
     * **The `uid` on each returned [InstalledApp] is a snapshot — never use it to apply
     * policy.** It reflects the uid at enumeration time only and goes stale the moment a
     * package is uninstalled and a different app is later installed under the same
     * package name. Callers that need to act on a uid must call [uidOf] with the package
     * name at apply time instead.
     */
    suspend fun load(): List<InstalledApp>

    /** Resolved at apply time, never stored — uids are reassigned on reinstall. */
    suspend fun uidOf(packageName: String): Int?
}

class PackageManagerAppRepository(private val context: Context) : InstalledAppRepository {

    private val pm: PackageManager get() = context.packageManager

    override suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(0)
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    uid = info.uid,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isEnabled = info.enabled,
                )
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
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
