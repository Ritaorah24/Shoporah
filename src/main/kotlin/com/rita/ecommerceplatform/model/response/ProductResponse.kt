package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Product
import com.rita.ecommerceplatform.model.entity.ProductImage
import java.math.BigDecimal

/**
 * Product as shown to the public: availability instead of the exact stock (D4), and no SKU,
 * status, creator or timestamps.
 */
data class ProductResponse(
    val productId: Long,
    val name: String,
    val description: String?,
    val categoryId: Int,
    val categoryName: String,
    val price: BigDecimal,
    val discountPrice: BigDecimal?,
    val sellingPrice: BigDecimal,
    val availability: String,
    // The main image (the thumbnail in lists); null when the product has no images.
    val primaryImageUrl: String?,
    // Primary first, then in the admin's order; empty when there are none.
    val images: List<ProductImageResponse>,
) {
    companion object {
        /** `images` in the order the repository returns them (primary first). */
        fun from(product: Product, images: List<ProductImage>) = ProductResponse(
            productId = product.productId,
            name = product.name,
            description = product.description,
            categoryId = product.categoryId,
            categoryName = product.categoryName,
            price = product.price,
            discountPrice = product.discountPrice,
            sellingPrice = product.sellingPrice,
            availability = product.availability().name,
            primaryImageUrl = images.firstOrNull { it.isPrimary }?.url,
            images = images.map(ProductImageResponse::from),
        )
    }
}
