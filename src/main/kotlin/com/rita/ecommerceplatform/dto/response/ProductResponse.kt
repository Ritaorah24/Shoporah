package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.Product
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
) {
    companion object {
        fun from(product: Product) = ProductResponse(
            productId = product.productId,
            name = product.name,
            description = product.description,
            categoryId = product.categoryId,
            categoryName = product.categoryName,
            price = product.price,
            discountPrice = product.discountPrice,
            sellingPrice = product.sellingPrice,
            availability = product.availability().name,
        )
    }
}
