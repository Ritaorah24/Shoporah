package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.ProductImage
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
            imageId = image.productImageId!!,
            productId = image.productImageProductId!!,
            url = image.productImageUrl!!,
            altText = image.productImageAltText,
            sortOrder = image.productImageSortOrder!!,
            primary = image.productImageIsPrimary!!,
            createdAt = image.productImageCreatedAt!!,
        )
    }
}
