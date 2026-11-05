package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/** One row of public.ec_category. */
data class Category(
    var categoryId: Int? = null,
    var categoryName: String? = null,
    var categoryDescription: String? = null,
    var categoryCreatedByAdminId: Int? = null,
    var categoryStatus: CategoryStatus? = null,
    var categoryCreatedAt: LocalDateTime? = null,
    var categoryUpdatedAt: LocalDateTime? = null,
)

/** Allowed values of ec_category.categorystatus (ck__ec_category__status). DELETED is a soft delete. */
enum class CategoryStatus { ACTIVE, INACTIVE, DELETED }
