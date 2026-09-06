// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.guardrail

enum class Sensitivity {
    /** Safe to restrict as far as we can tell. */
    NONE,

    /** Restricting this will probably break something the user relies on. */
    LIKELY_BREAKS,
}
