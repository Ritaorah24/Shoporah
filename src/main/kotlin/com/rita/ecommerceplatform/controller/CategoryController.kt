package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.dto.response.CategoryResponse
import com.rita.ecommerceplatform.service.CategoryService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Public: no token needed (SecurityConfig permits GET on every /api/categories URL). */
@Tag(name = "Categories", description = "Browse active categories")
@RestController
@RequestMapping("/api/categories")
class CategoryController(private val categoryService: CategoryService) {

    @GetMapping
    fun list(): List<CategoryResponse> = categoryService.listActive()
}
