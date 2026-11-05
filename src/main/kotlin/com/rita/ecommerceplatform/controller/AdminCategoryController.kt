package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.model.response.BaseResponse
import com.rita.ecommerceplatform.exception.AccessNotAllowedException
import com.rita.ecommerceplatform.config.SwaggerConfig
import com.rita.ecommerceplatform.model.request.CreateCategoryRequest
import com.rita.ecommerceplatform.model.request.UpdateCategoryRequest
import com.rita.ecommerceplatform.model.request.UpdateStatusRequest
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.CategoryService
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

/** Category management. SecurityConfig restricts every /{stage}/admin URL to admins who have changed their password. */
@Tag(name = "Admin categories", description = "Create, edit, activate/deactivate and delete categories")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
@RestController
@RequestMapping("/{stage}/admin/categories")
class AdminCategoryController(private val categoryService: CategoryService) {

    @GetMapping
    fun list(@PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", categoryService.listForAdmin())
    }

    @GetMapping("/{categoryId}")
    fun get(@PathVariable categoryId: Int, @PathVariable stage: String): BaseDataResponse {
        return BaseDataResponse("00", "Successful", categoryService.getForAdmin(categoryId))
    }

    /** The creating admin comes from the token, never from the request body. */
    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateCategoryRequest,
        @PathVariable stage: String,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", categoryService.create(adminId(jwt), request))
    }

    @PatchMapping("/{categoryId}")
    fun update(
        @PathVariable categoryId: Int,
        @Valid @RequestBody request: UpdateCategoryRequest,
        @PathVariable stage: String,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", categoryService.update(categoryId, request))
    }

    @PatchMapping("/{categoryId}/status")
    fun updateStatus(
        @PathVariable categoryId: Int,
        @Valid @RequestBody request: UpdateStatusRequest,
        @PathVariable stage: String,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", categoryService.updateStatus(categoryId, request))
    }

    @DeleteMapping("/{categoryId}")
    fun delete(@PathVariable categoryId: Int, @PathVariable stage: String): BaseResponse {
        categoryService.delete(categoryId)
        return BaseResponse()
    }

    // Roles already restrict this URL to admin tokens; this guards against a mis-issued token.
    private fun adminId(jwt: Jwt): Int {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.ADMIN) throw AccessNotAllowedException("Account is not active")
        return currentUser.id.toInt()
    }
}
