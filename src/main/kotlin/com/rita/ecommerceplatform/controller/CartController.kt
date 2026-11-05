package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.model.response.BaseResponse
import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.request.AddCartItemRequest
import com.rita.ecommerceplatform.model.request.UpdateCartItemRequest
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.CartService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The logged-in customer's cart. Changes return the whole cart, so the client never computes totals itself. */
@Tag(name = "Cart", description = "The logged-in customer's shopping cart")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/customer/cart")
class CartController(private val cartService: CartService) {

    @GetMapping
    fun get(@AuthenticationPrincipal jwt: Jwt): BaseDataResponse {
        return BaseDataResponse("00", "Successful", cartService.getCart(customerId(jwt)))
    }

    /** Adds to the quantity already in the cart. The total cannot exceed the product's stock. */
    @PostMapping("/items")
    fun addItem(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: AddCartItemRequest,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", cartService.addItem(customerId(jwt), request))
    }

    /** Sets the quantity (at least 1; use DELETE to remove). */
    @PatchMapping("/items/{productId}")
    fun updateItem(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable productId: Long,
        @Valid @RequestBody request: UpdateCartItemRequest,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", cartService.updateItem(customerId(jwt), productId, request))
    }

    @DeleteMapping("/items/{productId}")
    fun removeItem(@AuthenticationPrincipal jwt: Jwt, @PathVariable productId: Long): BaseResponse {
        cartService.removeItem(customerId(jwt), productId)
        return BaseResponse()
    }

    @DeleteMapping
    fun clear(@AuthenticationPrincipal jwt: Jwt): BaseResponse {
        cartService.clear(customerId(jwt))
        return BaseResponse()
    }

    // Roles already restrict /api/customer/** to customer tokens; this guards against a mis-issued token.
    private fun customerId(jwt: Jwt): Long {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.CUSTOMER) throw AccessNotAllowedException("Account is not active")
        return currentUser.id
    }
}
