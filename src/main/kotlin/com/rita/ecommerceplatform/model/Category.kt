package com.rita.ecommerceplatform.model

import java.time.LocalDateTime

/** One row of public.ec_category. */
data class Category(
    val categoryId: Int,
    val name: String,
    val description: String?,
    val createdByAdminId: Int,
    val status: CategoryStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_category.categorystatus (ck__ec_category__status). DELETED is a soft delete. */
enum class CategoryStatus { ACTIVE, INACTIVE, DELETED }
