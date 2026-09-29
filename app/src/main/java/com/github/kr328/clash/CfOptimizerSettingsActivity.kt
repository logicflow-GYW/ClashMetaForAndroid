package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.design.CfOptimizerSettingsDesign
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerIntents
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore
import com.github.kr328.clash.service.cfoptimizer.settings.KeystoreCfOptimizerSecretStore
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

class CfOptimizerSettingsActivity : BaseActivity<CfOptimizerSettingsDesign>() {
    override suspend fun main() {
        // Imported URL profiles selectable as the CF optimizer subscription target.
        // Service may not be connected yet — fall back to an empty list after a timeout.
        val urlProfiles = withTimeoutOrNull(PROFILE_QUERY_TIMEOUT) {
            withProfile {
                queryAll().filter { it.type == Profile.Type.Url && it.imported }
            }
        } ?: emptyList()

        val design = CfOptimizerSettingsDesign(
            this,
            CfOptimizerSettingsStore(this),
            KeystoreCfOptimizerSecretStore(this),
            urlProfiles,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { }

                design.requests.onReceive {
                    when (it) {
                        CfOptimizerSettingsDesign.Request.OpenParams -> {
                            startActivity(
                                Intent(this, CfOptimizerParamsActivity::class.java),
                            )
                        }
                        CfOptimizerSettingsDesign.Request.RunCfOptimizer -> {
                            CfOptimizerSettingsStore(this@CfOptimizerSettingsActivity).lastRunAt =
                                System.currentTimeMillis()

                            // Package-limited in-app broadcast; the receiver belongs
                            // to the service module. Never exported.
                            sendBroadcast(
                                Intent(CfOptimizerIntents.ACTION_RUN_NOW)
                                    .setPackage(packageName)
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val PROFILE_QUERY_TIMEOUT = 3_000L
    }
}
