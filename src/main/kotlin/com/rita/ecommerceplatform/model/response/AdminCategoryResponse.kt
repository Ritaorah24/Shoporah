package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Category
import java.time.LocalDateTime

/** Category as shown to admins: everything, including status and who created it. */
data class AdminCategoryResponse(
    val categoryId: Int,
    val name: String,
    val description: String?,
    val status: String,
    val createdByAdminId: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(category: Category) = AdminCategoryResponse(
            categoryId = category.categoryId!!,
            name = category.categoryName!!,
            description = category.categoryDescription,
            status = category.categoryStatus!!.name,
            createdByAdminId = category.categoryCreatedByAdminId!!,
            createdAt = category.categoryCreatedAt!!,
            updatedAt = category.categoryUpdatedAt!!,
        )
    }
}
