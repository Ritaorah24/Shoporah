package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/**
 * One row of public.ec_product_image: an image kept in the image storage. url is what browsers load;
 * storageKey is how the storage knows it (to delete it). At most one per product is primary (uq__ec_productimage__primary).
 */
data class ProductImage(
    val imageId: Long,
    val productId: Long,
    val url: String,
    val storageKey: String,
    val altText: String?,
    val sortOrder: Int,
    val isPrimary: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
