package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.model.entity.NotificationType
import com.rita.ecommerceplatform.service.NotificationService
import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The lifecycle emails of checkpoint 2.1, Step 4: each real event queues exactly one email. Paystack is the fake. */
class NotificationEventsTest : IntegrationTestBase() {

    @Autowired
    private lateinit var notificationService: NotificationService

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Value("\${app.paystack.secret-key}")
    private lateinit var secretKey: String

    private lateinit var adminToken: String
    private var categoryId: Int = 0

    private data class Shopper(val customerId: Long, val email: String, val token: String)

    private data class Row(val type: String, val status: String, val subject: String?, val body: String?, val failureReason: String?)

    @BeforeEach
    fun setUp() {
        adminToken = loginActiveAdmin()
        categoryId = createCategory()
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun newShopper(): Shopper {
        val email = uniqueEmail()
        val customerId = registerCustomer(email)
        val shopper = Shopper(customerId, email, loginCustomer(email))
        mockMvc.post("/api/customer/addresses") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json(
                "recipientName" to "Ada Obi",
                "recipientPhoneNumber" to "08012345678",
                "addressLine1" to "12 Allen Avenue",
                "city" to "Ikeja",
                "state" to "Lagos",
                "country" to "Nigeria",
            )
        }.andExpect { status { isCreated() } }
        return shopper
    }

    private fun createCategory(): Int {
        val result = mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Cat"))
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["categoryId"].asInt()
    }

    /** Puts 2 units of a new 1,000 product in the cart. */
    private fun fillCart(shopper: Shopper) {
        val product = mockMvc.post("/api/admin/products") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("name" to unique("Phone"), "sku" to unique("sku"), "categoryId" to categoryId, "price" to 1000, "stockQuantity" to 10)
        }.andExpect { status { isCreated() } }.andReturn()
        mockMvc.post("/api/customer/cart/items") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("productId" to readJson(product)["productId"].asLong(), "quantity" to 2)
        }.andExpect { status { isOk() } }
    }

    private fun checkout(shopper: Shopper, key: UUID = UUID.randomUUID()): ResultActionsDsl =
        mockMvc.post("/api/customer/orders") {
            header("Authorization", "Bearer ${shopper.token}")
            header("Idempotency-Key", key.toString())
        }

    /** A new PENDING order; returns its orderId and reference. */
    private fun placeOrder(shopper: Shopper): Pair<Long, String> {
        fillCart(shopper)
        val order = readJson(checkout(shopper).andExpect { status { isCreated() } }.andReturn())
        return order["orderId"].asLong() to order["reference"].asString()
    }

    private fun initialize(shopper: Shopper, orderReference: String): String {
        val result = mockMvc.post("/api/customer/payments/initialize") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("orderReference" to orderReference)
        }.andExpect { status { isCreated() } }.andReturn()
        return readJson(result)["paymentReference"].asString()
    }

    private fun verify(shopper: Shopper, paymentReference: String) {
        mockMvc.get("/api/customer/payments/verify/$paymentReference") { header("Authorization", "Bearer ${shopper.token}") }
            .andExpect { status { isOk() } }
    }

    private fun webhook(paymentReference: String) {
        val body = """{"event":"charge.success","data":{"reference":"$paymentReference"}}"""
        val signature = HexFormat.of().formatHex(
            Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA512")) }.doFinal(body.toByteArray())
        )
        mockMvc.post("/api/webhooks/paystack") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("x-paystack-signature", signature)
        }.andExpect { status { isOk() } }
    }

    private fun move(orderId: Long, status: String, note: String? = null) {
        mockMvc.patch("/api/admin/orders/$orderId/status") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = json("status" to status, "note" to note)
        }.andExpect { status { isOk() } }
    }

    private fun rows(email: String, type: NotificationType? = null): List<Row> =
        jdbcClient.sql(
            """
            SELECT notificationtype::text, notificationstatus::text, notificationsubject::text,
                   notificationbody, notificationfailurereason
            FROM public.ec_notification
            WHERE notificationrecipient = :email AND (CAST(:type AS text) IS NULL OR notificationtype = :type)
            ORDER BY notificationid
            """
        )
            .param("email", email)
            .param("type", type?.name)
            .query { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }
            .list()

    private fun orderRows(orderId: Long, type: NotificationType): Int =
        jdbcClient.sql("SELECT COUNT(*) FROM public.ec_notification WHERE notificationorderid = :id AND notificationtype = :type")
            .param("id", orderId)
            .param("type", type.name)
            .query(Int::class.javaObjectType)
            .single()

    private fun subjects(email: String): List<String?> = emailChannel.sentTo(email).map { it.subject }

    @Test
    fun `registering sends one welcome email`() {
        val email = uniqueEmail()
        registerCustomer(email)
        assertEquals(listOf("PENDING"), rows(email, NotificationType.REGISTRATION).map { it.status })

        drainOutbox()

        assertEquals(listOf("SENT"), rows(email, NotificationType.REGISTRATION).map { it.status })
        assertEquals(listOf("Welcome to Shoporah"), subjects(email))
        assertTrue(emailChannel.sentTo(email).single().body!!.startsWith("Hi Test,"))
    }

    @Test
    fun `a new order sends one confirmation, and a retry with the same key sends none`() {
        val shopper = newShopper()
        fillCart(shopper)
        val key = UUID.randomUUID()
        val order = readJson(checkout(shopper, key).andExpect { status { isCreated() } }.andReturn())
        val orderId = order["orderId"].asLong()
        val reference = order["reference"].asString()

        checkout(shopper, key).andExpect { status { isOk() } }
        assertEquals(1, orderRows(orderId, NotificationType.ORDER_CONFIRMATION))

        drainOutbox()
        val email = emailChannel.sentTo(shopper.email).single { it.subject == "We received your order $reference" }
        assertTrue(email.body!!.contains("x 2: ₦2,000.00"), email.body)
        assertTrue(email.body!!.contains("Total: ₦2,000.00"), email.body)
    }

    @Test
    fun `a verified payment sends one confirmation even when verify and the webhook both run`() {
        val shopper = newShopper()
        val (orderId, reference) = placeOrder(shopper)
        val paymentReference = initialize(shopper, reference)
        paymentGateway.pay(paymentReference)

        verify(shopper, paymentReference)
        webhook(paymentReference)
        verify(shopper, paymentReference)

        assertEquals(1, orderRows(orderId, NotificationType.PAYMENT_CONFIRMATION))
        drainOutbox()
        assertEquals(1, subjects(shopper.email).count { it == "Payment received for order $reference" })
    }

    @Test
    fun `a failed payment sends no payment email`() {
        val shopper = newShopper()
        val (orderId, reference) = placeOrder(shopper)
        val paymentReference = initialize(shopper, reference)
        paymentGateway.fail(paymentReference)

        verify(shopper, paymentReference)

        assertEquals(0, orderRows(orderId, NotificationType.PAYMENT_CONFIRMATION))
    }

    @Test
    fun `shipped and delivered each send one email, processing sends none`() {
        val shopper = newShopper()
        val (orderId, reference) = placeOrder(shopper)
        val paymentReference = initialize(shopper, reference)
        paymentGateway.pay(paymentReference)
        verify(shopper, paymentReference)
        drainOutbox()
        val before = emailChannel.sentTo(shopper.email).size

        move(orderId, "PROCESSING")
        drainOutbox()
        assertEquals(before, emailChannel.sentTo(shopper.email).size)

        move(orderId, "SHIPPED", note = "GIG Logistics, tracking 12345")
        move(orderId, "DELIVERED")
        assertEquals(1, orderRows(orderId, NotificationType.ORDER_SHIPPED))
        assertEquals(1, orderRows(orderId, NotificationType.ORDER_DELIVERED))

        drainOutbox()
        val shipped = emailChannel.sentTo(shopper.email).single { it.subject == "Your order $reference is on its way" }
        assertTrue(shipped.body!!.contains("Details: GIG Logistics, tracking 12345"), shipped.body)
        assertEquals(1, subjects(shopper.email).count { it == "Your order $reference was delivered" })
    }

    @Test
    fun `changing and resetting the password each send a password changed email`() {
        val shopper = newShopper()

        mockMvc.patch("/api/customer/me/password") {
            header("Authorization", "Bearer ${shopper.token}")
            contentType = MediaType.APPLICATION_JSON
            content = json("currentPassword" to DEFAULT_PASSWORD, "newPassword" to "changed456")
        }.andExpect { status { isNoContent() } }
        assertEquals(1, rows(shopper.email, NotificationType.PASSWORD_CHANGED).size)

        mockMvc.post("/api/auth/forgot-password") {
            contentType = MediaType.APPLICATION_JSON
            content = json("email" to shopper.email)
        }.andExpect { status { isAccepted() } }
        val token = awaitResetToken(shopper.email)
        mockMvc.post("/api/auth/reset-password") {
            contentType = MediaType.APPLICATION_JSON
            content = json("token" to token, "newPassword" to "reset789x")
        }.andExpect { status { isNoContent() } }
        assertEquals(2, rows(shopper.email, NotificationType.PASSWORD_CHANGED).size)

        drainOutbox()
        assertEquals(2, subjects(shopper.email).count { it == "Your Shoporah password was changed" })
    }

    @Test
    fun `a second email for the same order event is skipped without an error`() {
        val shopper = newShopper()
        val (orderId, _) = placeOrder(shopper)

        val duplicateId = transactionTemplate.execute {
            notificationService.queue(
                NotificationType.ORDER_CONFIRMATION, shopper.email, "Again", "Again",
                customerId = shopper.customerId, orderId = orderId,
            )
        }

        assertNull(duplicateId)
        assertEquals(1, orderRows(orderId, NotificationType.ORDER_CONFIRMATION))
    }

    @Test
    fun `checkout still succeeds when email is down, and the email waits to be retried`() {
        val shopper = newShopper()
        try {
            emailChannel.failWith = "SMTP server unreachable"
            val (orderId, _) = placeOrder(shopper)
            drainOutbox()

            assertEquals(1, orderRows(orderId, NotificationType.ORDER_CONFIRMATION))
            val row = rows(shopper.email, NotificationType.ORDER_CONFIRMATION).single()
            assertEquals("PENDING", row.status)
            assertEquals("SMTP server unreachable", row.failureReason)
        } finally {
            emailChannel.failWith = null
        }
    }

    // The reset email goes out on a background thread, so wait (up to 5 s) for it.
    private fun awaitResetToken(email: String): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            emailChannel.sentTo(email).firstOrNull { it.subject == "Reset your Shoporah password" }?.let { sent ->
                return Regex("token=([A-Za-z0-9_-]+)").find(sent.body!!)!!.groupValues[1]
            }
            Thread.sleep(50)
        }
        fail("No reset email to $email")
    }
}
