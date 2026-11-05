package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.response.AdminCustomerResponse
import com.rita.ecommerceplatform.model.response.AdminCustomerSummaryResponse
import com.rita.ecommerceplatform.model.response.PageResponse
import com.rita.ecommerceplatform.service.AdminCustomerService
import com.rita.ecommerceplatform.service.Paging
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Customer lookup for admins. SecurityConfig restricts every /api/admin URL to admins who have changed their password. */
@Tag(name = "Admin customers", description = "Find customers and see their order totals")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/admin/customers")
class AdminCustomerController(private val adminCustomerService: AdminCustomerService) {

    /**
     * Newest first; page size is capped at 50. search matches part of the email, full name or phone number;
     * status is ACTIVE, SUSPENDED, DEACTIVATED or DELETED (default: all).
     */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) status: String?,
    ): PageResponse<AdminCustomerSummaryResponse> = adminCustomerService.list(page, size, search, status)

    /** The customer with their order totals; their orders: GET /api/admin/orders?customerId={customerId}. */
    @GetMapping("/{customerId}")
    fun get(@PathVariable customerId: Long): AdminCustomerResponse = adminCustomerService.get(customerId)
}
