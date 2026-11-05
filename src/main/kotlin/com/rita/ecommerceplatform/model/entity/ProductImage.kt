package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/**
 * One row of public.ec_product_image: an image kept in the image storage. url is what browsers load;
 * storageKey is how the storage knows it (to delete it). At most one per product is primary (uq__ec_productimage__primary).
 */
data class ProductImage(
    var productImageId: Long? = null,
    var productImageProductId: Long? = null,
    var productImageUrl: String? = null,
    var productImageStorageKey: String? = null,
    var productImageAltText: String? = null,
    var productImageSortOrder: Int? = null,
    var productImageIsPrimary: Boolean? = null,
    var productImageCreatedAt: LocalDateTime? = null,
    var productImageUpdatedAt: LocalDateTime? = null,
)
