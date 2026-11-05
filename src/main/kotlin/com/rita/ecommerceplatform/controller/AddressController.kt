package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.model.response.BaseResponse
import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.request.CreateAddressRequest
import com.rita.ecommerceplatform.model.request.UpdateAddressRequest
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.AddressService
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

/** The logged-in customer's addresses: the customer always comes from the token, never from the request. */
@Tag(name = "Customer addresses", description = "The logged-in customer's delivery addresses")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/{stage}/customer/addresses")
class AddressController(private val addressService: AddressService) {

    /** Default address first, then newest first. */
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", addressService.list(customerId(jwt)))
    }

    @GetMapping("/{addressId}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable addressId: Long, @PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", addressService.get(customerId(jwt), addressId))
    }

    /** At most 10 addresses. The first one is always the default. */
    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateAddressRequest,
        @PathVariable stage: String,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", addressService.create(customerId(jwt), request))
    }

    @PatchMapping("/{addressId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable addressId: Long,
        @Valid @RequestBody request: UpdateAddressRequest,
        @PathVariable stage: String,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", addressService.update(customerId(jwt), addressId, request))
    }

    @PatchMapping("/{addressId}/default")
    fun setDefault(@AuthenticationPrincipal jwt: Jwt, @PathVariable addressId: Long, @PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", addressService.setDefault(customerId(jwt), addressId))
    }

    /** Deleting the default address leaves no default. */
    @DeleteMapping("/{addressId}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable addressId: Long, @PathVariable stage: String): BaseResponse {
        addressService.delete(customerId(jwt), addressId)
        return BaseResponse()
    }

    // Roles already restrict /{stage}/customer/** to customer tokens; this guards against a mis-issued token.
    private fun customerId(jwt: Jwt): Long {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.CUSTOMER) throw AccessNotAllowedException("Account is not active")
        return currentUser.id
    }
}
