package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Category

/** Category as shown to the public: no status, creator or timestamps. */
data class CategoryResponse(
    val categoryId: Int,
    val name: String,
    val description: String?,
) {
    companion object {
        fun from(category: Category) = CategoryResponse(
            categoryId = category.categoryId,
            name = category.name,
            description = category.description,
        )
    }
}
