package com.jinatra.hiberna.privilege

enum class PrivilegeState {
    /** Shizuku is not installed at all. */
    SHIZUKU_ABSENT,
    /** Installed, but the service is not running — the user must start it. */
    SERVICE_NOT_RUNNING,
    /** Running, but this app has not been granted access. */
    PERMISSION_DENIED,
    /** Privileged commands can run. */
    READY,
}
