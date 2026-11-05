package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.model.request.CreateProductRequest
import com.rita.ecommerceplatform.model.request.StockAdjustmentRequest
import com.rita.ecommerceplatform.model.request.UpdateProductRequest
import com.rita.ecommerceplatform.model.request.UpdateStatusRequest
import com.rita.ecommerceplatform.model.response.AdminProductResponse
import com.rita.ecommerceplatform.model.response.PageResponse
import com.rita.ecommerceplatform.model.response.StockMovementResponse
import com.rita.ecommerceplatform.exception.AccountNotActiveException
import com.rita.ecommerceplatform.security.CurrentUser
import com.rita.ecommerceplatform.security.UserType
import com.rita.ecommerceplatform.service.Paging
import com.rita.ecommerceplatform.service.ProductFilters
import com.rita.ecommerceplatform.service.ProductService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/** Product management. SecurityConfig restricts every /api/admin URL to admins who have changed their password. */
@Tag(name = "Admin products", description = "Create, edit, activate/deactivate and delete products")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/admin/products")
class AdminProductController(private val productService: ProductService) {

    /**
     * Page size is capped at 50; status filters to ACTIVE or INACTIVE (default: both).
     * minPrice, maxPrice, inStock and sort work as on the public list. stock=OUT lists products with no stock;
     * stock=LOW those at or below their lowStockThreshold (products without a threshold are never low).
     */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) categoryId: Int?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) inStock: Boolean?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) stock: String?,
    ): PageResponse<AdminProductResponse> =
        productService.listForAdmin(page, size, search, categoryId, status, ProductFilters(minPrice, maxPrice, inStock, sort), stock)

    @GetMapping("/{productId}")
    fun get(@PathVariable productId: Long): AdminProductResponse = productService.getForAdmin(productId)

    /** The creating admin comes from the token, never from the request body. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateProductRequest,
    ): AdminProductResponse = productService.create(adminId(jwt), request)

    /** The admin is recorded on the inventory log when the stock changes. */
    @PatchMapping("/{productId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable productId: Long,
        @Valid @RequestBody request: UpdateProductRequest,
    ): AdminProductResponse = productService.update(adminId(jwt), productId, request)

    @PatchMapping("/{productId}/status")
    fun updateStatus(
        @PathVariable productId: Long,
        @Valid @RequestBody request: UpdateStatusRequest,
    ): AdminProductResponse = productService.updateStatus(productId, request)

    /**
     * Adds or removes stock with a reason: change is +N (delivery, found) or -N (damaged, lost), never the new total,
     * so a sale made meanwhile is kept. Stock never goes below 0 (409). Preferred over editing stockQuantity.
     */
    @PostMapping("/{productId}/stock-adjustments")
    fun adjustStock(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable productId: Long,
        @Valid @RequestBody request: StockAdjustmentRequest,
    ): AdminProductResponse = productService.adjustStock(adminId(jwt), productId, request)

    /** Every stock change of the product, newest first: who, why, which order and the stock after. */
    @GetMapping("/{productId}/stock-movements")
    fun stockMovements(
        @PathVariable productId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
    ): PageResponse<StockMovementResponse> = productService.stockMovements(productId, page, size)

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable productId: Long) = productService.delete(productId)

    // Roles already restrict this URL to admin tokens; this guards against a mis-issued token.
    private fun adminId(jwt: Jwt): Int {
        val currentUser = CurrentUser.from(jwt)
        if (currentUser.userType != UserType.ADMIN) throw AccountNotActiveException()
        return currentUser.id.toInt()
    }
}
