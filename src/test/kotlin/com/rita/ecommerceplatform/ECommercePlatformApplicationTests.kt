package com.rita.ecommerceplatform

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.Test

/** Extends IntegrationTestBase so it uses the test profile (ecommerce_test), never ecommerce_dev. */
class ECommercePlatformApplicationTests : IntegrationTestBase() {

    @Test
    fun contextLoads() {
    }

}
