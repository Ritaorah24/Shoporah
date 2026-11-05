package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.request.CreateDeliveryMethodRequest
import com.rita.ecommerceplatform.model.request.UpdateDeliveryMethodRequest
import com.rita.ecommerceplatform.model.request.UpdateStatusRequest
import com.rita.ecommerceplatform.service.DeliveryMethodService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Delivery method management. SecurityConfig restricts every /api/admin URL to admins who have changed their password. */
@Tag(name = "Admin delivery methods", description = "Create, edit and activate/deactivate delivery methods and their fees")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/admin/delivery-methods")
class AdminDeliveryMethodController(private val deliveryMethodService: DeliveryMethodService) {

    @GetMapping
    fun list(): BaseDataResponse {
        return BaseDataResponse("00", "Successful", deliveryMethodService.listForAdmin())
    }

    @GetMapping("/{deliveryMethodId}")
    fun get(@PathVariable deliveryMethodId: Int): BaseDataResponse {
        return BaseDataResponse("00", "Successful", deliveryMethodService.getForAdmin(deliveryMethodId))
    }

    @PostMapping
    fun create(@Valid @RequestBody request: CreateDeliveryMethodRequest): BaseDataResponse {
        return BaseDataResponse("00", "Successful", deliveryMethodService.create(request))
    }

    @PatchMapping("/{deliveryMethodId}")
    fun update(
        @PathVariable deliveryMethodId: Int,
        @Valid @RequestBody request: UpdateDeliveryMethodRequest,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", deliveryMethodService.update(deliveryMethodId, request))
    }

    @PatchMapping("/{deliveryMethodId}/status")
    fun updateStatus(
        @PathVariable deliveryMethodId: Int,
        @Valid @RequestBody request: UpdateStatusRequest,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", deliveryMethodService.updateStatus(deliveryMethodId, request))
    }
}
