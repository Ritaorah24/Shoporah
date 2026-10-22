package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.repository.api.PaymentGateway
import com.rita.ecommerceplatform.support.FakePaymentGateway
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertSame

/** Guards the test setup (checkpoint 1.4, Step 1): wherever the app asks for a PaymentGateway, it gets the fake. */
class PaymentGatewayWiringTest : IntegrationTestBase() {

    @Autowired
    private lateinit var injected: PaymentGateway

    @Test
    fun `the app uses the fake gateway in tests, never Paystack`() {
        assertSame<PaymentGateway>(paymentGateway, injected)
        assertSame<Class<*>>(FakePaymentGateway::class.java, injected.javaClass)
    }
}
