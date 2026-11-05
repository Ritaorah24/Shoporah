package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.service.RefundService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Refunds are started on the order (POST /{stage}/admin/orders/{orderId}/refund). SecurityConfig restricts /{stage}/admin to admins. */
@Tag(name = "Admin refunds", description = "Check on refunds that Paystack is still processing")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/{stage}/admin/refunds")
class AdminRefundController(private val refundService: RefundService) {

    /**
     * Asks Paystack about a PENDING refund and applies the outcome once: PROCESSED marks the order REFUNDED and
     * emails the customer; FAILED leaves the order as it was so it can be refunded again. Returns the order.
     */
    @PostMapping("/{refundId}/refresh")
    fun refresh(@PathVariable refundId: Long, @PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", refundService.refresh(refundId))
    }
}
