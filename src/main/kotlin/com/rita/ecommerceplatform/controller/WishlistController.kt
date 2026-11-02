package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.dto.request.AddWishlistItemRequest
import com.rita.ecommerceplatform.dto.request.MoveToCartRequest
import com.rita.ecommerceplatform.dto.response.WishlistResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.WishlistService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** The logged-in customer's wishlist. Changes return the whole wishlist. */
@Tag(name = "Wishlist", description = "Products the logged-in customer saved for later")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/customer/wishlist")
class WishlistController(private val wishlistService: WishlistService) {

    /** Newest first, with current prices and availability. */
    @GetMapping
    fun get(@AuthenticationPrincipal jwt: Jwt): WishlistResponse = wishlistService.get(customerId(jwt))

    /** Saves a product (also when out of stock). Adding one that is already there changes nothing. At most 100. */
    @PostMapping("/items")
    fun addItem(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: AddWishlistItemRequest,
    ): WishlistResponse = wishlistService.addItem(customerId(jwt), request)

    @DeleteMapping("/items/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeItem(@AuthenticationPrincipal jwt: Jwt, @PathVariable productId: Long) =
        wishlistService.removeItem(customerId(jwt), productId)

    /**
     * Adds the product to the cart (quantity 1 unless sent; never above the stock, 409) and takes it off the
     * wishlist. If the cart refuses, the item stays on the wishlist. Returns the updated wishlist.
     */
    @PostMapping("/items/{productId}/move-to-cart")
    fun moveToCart(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable productId: Long,
        @Valid @RequestBody(required = false) request: MoveToCartRequest?,
    ): WishlistResponse = wishlistService.moveToCart(customerId(jwt), productId, request)

    // Roles already restrict /api/customer/** to customer tokens; this guards against a mis-issued token.
    private fun customerId(jwt: Jwt): Long {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.CUSTOMER) throw AccountNotActiveException()
        return currentUser.id
    }
}
