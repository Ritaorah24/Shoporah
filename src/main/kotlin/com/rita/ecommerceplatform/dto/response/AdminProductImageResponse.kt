package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.ProductImage
import java.time.LocalDateTime

/** One product image as admins see it. The storage key stays internal. */
data class AdminProductImageResponse(
    val imageId: Long,
    val productId: Long,
    val url: String,
    val altText: String?,
    val sortOrder: Int,
    val primary: Boolean,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(image: ProductImage) = AdminProductImageResponse(
            imageId = image.imageId,
            productId = image.productId,
            url = image.url,
            altText = image.altText,
            sortOrder = image.sortOrder,
            primary = image.isPrimary,
            createdAt = image.createdAt,
        )
    }
}
