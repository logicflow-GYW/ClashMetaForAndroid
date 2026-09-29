package com.github.kr328.clash

import com.github.kr328.clash.design.CfOptimizerParamsDesign
import com.github.kr328.clash.service.cfoptimizer.settings.CfOptimizerSettingsStore
import kotlinx.coroutines.isActive

class CfOptimizerParamsActivity : BaseActivity<CfOptimizerParamsDesign>() {
    override suspend fun main() {
        val design = CfOptimizerParamsDesign(this, CfOptimizerSettingsStore(this))

        setContentDesign(design)

        while (isActive) {
            events.receive()
        }
    }
}
