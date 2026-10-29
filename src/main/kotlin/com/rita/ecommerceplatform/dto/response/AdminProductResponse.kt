package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.Product
import com.rita.ecommerceplatform.model.ProductImage
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
    // Primary first, then by sortOrder; managed at /api/admin/products/{productId}/images.
    val images: List<AdminProductImageResponse>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        /** `images` in the order the repository returns them (primary first). */
        fun from(product: Product, images: List<ProductImage>) = AdminProductResponse(
            productId = product.productId,
            name = product.name,
            description = product.description,
            sku = product.sku,
            categoryId = product.categoryId,
            categoryName = product.categoryName,
            price = product.price,
            discountPrice = product.discountPrice,
            sellingPrice = product.sellingPrice,
            stockQuantity = product.stockQuantity,
            lowStockThreshold = product.lowStockThreshold,
            availability = product.availability().name,
            status = product.status.name,
            createdByAdminId = product.createdByAdminId,
            primaryImageUrl = images.firstOrNull { it.isPrimary }?.url,
            images = images.map(AdminProductImageResponse::from),
            createdAt = product.createdAt,
            updatedAt = product.updatedAt,
        )
    }
}
