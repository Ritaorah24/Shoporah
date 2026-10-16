package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.Category
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
            categoryId = category.categoryId,
            name = category.name,
            description = category.description,
            status = category.status.name,
            createdByAdminId = category.createdByAdminId,
            createdAt = category.createdAt,
            updatedAt = category.updatedAt,
        )
    }
}
