package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerIntents
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore
import com.github.kr328.clash.service.cfoptimizer.settings.KeystoreCfOptimizerSecretStore
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {
    override suspend fun main() {
        // Imported URL profiles selectable as the CF optimizer subscription target.
        // Service may not be connected yet — fall back to an empty list after a timeout.
        val urlProfiles = withTimeoutOrNull(PROFILE_QUERY_TIMEOUT) {
            withProfile {
                queryAll().filter { it.type == Profile.Type.Url && it.imported }
            }
        } ?: emptyList()

        val design = NetworkSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            clashRunning,
            CfOptimizerSettingsStore(this),
            KeystoreCfOptimizerSecretStore(this),
            urlProfiles,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NetworkSettingsDesign.Request.StartAccessControlList ->
                            startActivity(AccessControlActivity::class.intent)
                        NetworkSettingsDesign.Request.RunCfOptimizer -> {
                            CfOptimizerSettingsStore(this@NetworkSettingsActivity).lastRunAt =
                                System.currentTimeMillis()

                            // Package-limited in-app broadcast; the receiver side belongs
                            // to the integration module. Never exported.
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

    private companion object {
        const val PROFILE_QUERY_TIMEOUT = 3_000L
    }
}
