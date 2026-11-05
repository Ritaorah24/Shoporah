package com.rita.ecommerceplatform.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Checks that a webhook really comes from Paystack (https://paystack.com/docs/payments/webhooks/).
 * Paystack sends, in the x-paystack-signature header, the HMAC-SHA512 of the exact request body made with
 * our secret key, in hex. Only Paystack and we know the key, so nobody else can produce a matching signature.
 */
@Component
class PaystackWebhookVerifier(@Value("\${app.paystack.secret-key}") secretKey: String) {

    private val key = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), ALGORITHM)

    /** True only if the signature matches the body byte for byte. A missing signature is never valid. */
    fun isValid(body: ByteArray, signature: String?): Boolean {
        if (signature.isNullOrBlank()) return false
        val expected = HexFormat.of().formatHex(Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(body))
        // Constant time: == stops at the first different character, which leaks how much of a guess was right.
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.trim().lowercase().toByteArray(Charsets.UTF_8),
        )
    }

    companion object {
        private const val ALGORITHM = "HmacSHA512"
    }
}
