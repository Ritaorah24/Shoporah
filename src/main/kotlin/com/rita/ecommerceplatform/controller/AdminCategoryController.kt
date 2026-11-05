package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.request.CreateCategoryRequest
import com.rita.ecommerceplatform.model.request.UpdateCategoryRequest
import com.rita.ecommerceplatform.model.request.UpdateStatusRequest
import com.rita.ecommerceplatform.model.response.AdminCategoryResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.CategoryService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Category management. SecurityConfig restricts every /api/admin URL to admins who have changed their password. */
@Tag(name = "Admin categories", description = "Create, edit, activate/deactivate and delete categories")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/admin/categories")
class AdminCategoryController(private val categoryService: CategoryService) {

    @GetMapping
    fun list(): List<AdminCategoryResponse> = categoryService.listForAdmin()

    @GetMapping("/{categoryId}")
    fun get(@PathVariable categoryId: Int): AdminCategoryResponse = categoryService.getForAdmin(categoryId)

    /** The creating admin comes from the token, never from the request body. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateCategoryRequest,
    ): AdminCategoryResponse = categoryService.create(adminId(jwt), request)

    @PatchMapping("/{categoryId}")
    fun update(
        @PathVariable categoryId: Int,
        @Valid @RequestBody request: UpdateCategoryRequest,
    ): AdminCategoryResponse = categoryService.update(categoryId, request)

    @PatchMapping("/{categoryId}/status")
    fun updateStatus(
        @PathVariable categoryId: Int,
        @Valid @RequestBody request: UpdateStatusRequest,
    ): AdminCategoryResponse = categoryService.updateStatus(categoryId, request)

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable categoryId: Int) = categoryService.delete(categoryId)

    // Roles already restrict this URL to admin tokens; this guards against a mis-issued token.
    private fun adminId(jwt: Jwt): Int {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.ADMIN) throw AccountNotActiveException()
        return currentUser.id.toInt()
    }
}
