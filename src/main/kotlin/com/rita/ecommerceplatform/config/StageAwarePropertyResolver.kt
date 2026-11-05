package com.rita.ecommerceplatform.config

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource

/**
 * Lets one local, untracked properties file hold BOTH dev and prod
 * credentials side by side, keyed by stage:
 *
 *   app.stage=dev
 *   datasource.dev.password=xxxxx
 *   datasource.prod.password=yyyyy
 *
 * For every key shaped `<prefix>.<stage>.<rest>` where <stage> matches the
 * active app.stage, this synthesizes the bare key `<prefix>.<rest>` with
 * that value, at highest precedence. Flip app.stage, nothing else changes.
 */
class StageAwarePropertyResolver : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val stage = environment.getProperty("app.stage") ?: return
        val marker = ".$stage."
        val resolved = mutableMapOf<String, Any>()

        for (source in environment.propertySources) {
            if (source !is EnumerablePropertySource<*>) continue
            for (key in source.propertyNames) {
                val idx = key.indexOf(marker)
                if (idx <= 0) continue
                val bareKey = key.substring(0, idx) + "." + key.substring(idx + marker.length)
                val value = source.getProperty(key) ?: continue
                resolved[bareKey] = value
            }
        }

        if (resolved.isNotEmpty()) {
            environment.propertySources.addFirst(MapPropertySource("stageAwareOverrides", resolved))
        }
    }
}
