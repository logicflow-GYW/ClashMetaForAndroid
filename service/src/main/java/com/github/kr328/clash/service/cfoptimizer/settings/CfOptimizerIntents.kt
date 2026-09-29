package com.github.kr328.clash.service.cfoptimizer.settings

/**
 * CF optimizer run-now trigger action. Sent as a package-limited (non-exported)
 * in-app broadcast from the settings UI; the receiver/handler side belongs to the
 * integration module (D), not to settings.
 */
object CfOptimizerIntents {
    const val ACTION_RUN_NOW = "com.github.metacubex.clash.action.RUN_CF_OPTIMIZER"
}
