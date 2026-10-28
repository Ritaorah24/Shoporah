package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.dto.response.DeliveryMethodResponse
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
    fun list(): List<DeliveryMethodResponse> = deliveryMethodService.listActive()
}
