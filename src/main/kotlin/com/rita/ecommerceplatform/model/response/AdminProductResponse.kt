package com.rita.ecommerceplatform.model.response

import com.rita.ecommerceplatform.model.entity.Product
import com.rita.ecommerceplatform.model.entity.ProductImage
import java.math.BigDecimal
import java.time.LocalDateTime

/** Product as shown to admins: everything, including the exact stock and status. */
data class AdminProductResponse(
    val productId: Long,
    val name: String,
    val description: String?,
    val sku: String,
    val categoryId: Int,
    val categoryName: String,
    val price: BigDecimal,
    val discountPrice: BigDecimal?,
    val sellingPrice: BigDecimal,
    val stockQuantity: Int,
    val lowStockThreshold: Int?,
    // IN_STOCK, LOW_STOCK or OUT_OF_STOCK: what the public sees, and what ?stock=LOW|OUT filters on.
    val availability: String,
    val status: String,
    val createdByAdminId: Int,
    // The main image; null when the product has no images.
    val primaryImageUrl: String?,
    // Primary first, then by sortOrder; managed at /{stage}/admin/products/{productId}/images.
    val images: List<AdminProductImageResponse>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        /** `images` in the order the repository returns them (primary first). */
        fun from(product: Product, images: List<ProductImage>) = AdminProductResponse(
            productId = product.productId!!,
            name = product.productName!!,
            description = product.productDescription,
            sku = product.productSku!!,
            categoryId = product.productCategoryId!!,
            categoryName = product.categoryName!!,
            price = product.productPrice!!,
            discountPrice = product.productDiscountPrice,
            sellingPrice = product.productSellingPrice!!,
            stockQuantity = product.productStockQuantity!!,
            lowStockThreshold = product.productLowStockThreshold,
            availability = product.availability().name,
            status = product.productStatus!!.name,
            createdByAdminId = product.productCreatedByAdminId!!,
            primaryImageUrl = images.firstOrNull { it.productImageIsPrimary!! }?.productImageUrl,
            images = images.map(AdminProductImageResponse::from),
            createdAt = product.productCreatedAt!!,
            updatedAt = product.productUpdatedAt!!,
        )
    }
}
