package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.service.DeliveryMethodService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Public: no token needed (SecurityConfig permits GET on every /api/delivery-methods URL). */
@Tag(name = "Delivery methods", description = "Delivery options and fees to choose from at checkout")
@RestController
@RequestMapping("/api/delivery-methods")
class DeliveryMethodController(private val deliveryMethodService: DeliveryMethodService) {

    @GetMapping
    fun list(): BaseDataResponse {
        return BaseDataResponse("00", "Successful", deliveryMethodService.listActive())
    }
}
