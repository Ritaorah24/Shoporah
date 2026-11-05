package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.service.Paging
import com.rita.ecommerceplatform.service.ProductFilters
import com.rita.ecommerceplatform.service.ProductService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/** Public: no token needed (SecurityConfig permits GET on every /api/products URL). */
@Tag(name = "Products", description = "Browse active products")
@RestController
@RequestMapping("/api/products")
class ProductController(private val productService: ProductService) {

    /**
     * Page size is capped at 50. search matches part of the name, ignoring case. minPrice/maxPrice are on the
     * selling price; inStock=true hides sold-out products; sort is newest (default), price_asc or price_desc.
     */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) categoryId: Int?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) inStock: Boolean?,
        @RequestParam(required = false) sort: String?,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", productService.listPublic(page, size, search, categoryId, ProductFilters(minPrice, maxPrice, inStock, sort)))
    }

    @GetMapping("/{productId}")
    fun get(@PathVariable productId: Long): BaseDataResponse {
        return BaseDataResponse("00", "Successful", productService.getPublic(productId))
    }
}
