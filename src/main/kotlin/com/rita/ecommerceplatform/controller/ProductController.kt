package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.dto.response.ProductResponse
import com.rita.ecommerceplatform.service.Paging
import com.rita.ecommerceplatform.service.ProductService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Public: no token needed (SecurityConfig permits GET on every /api/products URL). */
@Tag(name = "Products", description = "Browse active products")
@RestController
@RequestMapping("/api/products")
class ProductController(private val productService: ProductService) {

    /** Page size is capped at 50. search matches part of the name, ignoring case. */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${Paging.DEFAULT_SIZE}") size: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) categoryId: Int?,
    ): PageResponse<ProductResponse> = productService.listPublic(page, size, search, categoryId)

    @GetMapping("/{productId}")
    fun get(@PathVariable productId: Long): ProductResponse = productService.getPublic(productId)
}
