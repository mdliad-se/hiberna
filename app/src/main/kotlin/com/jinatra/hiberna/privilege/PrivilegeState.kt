// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.privilege

enum class PrivilegeState {
    /**
     * Nothing has been evaluated yet. The gate starts here because deciding
     * requires blocking binder calls, and doing those in a constructor means
     * doing them on whatever thread built the object - usually the main one.
     */
    CHECKING,

    /**
     * No Shizuku binder and no Shizuku manager package: the user has to
     * install something. Only ever reported when *both* are missing, so Sui
     * users - who have a live binder and no Shizuku package - never land here.
     */
    SHIZUKU_ABSENT,

    /** Installed, but the service is not running - the user must start it. */
    SERVICE_NOT_RUNNING,

    /** Running, but this app has not been granted access. */
    PERMISSION_DENIED,

    /** Privileged commands can run. */
    READY,
}
