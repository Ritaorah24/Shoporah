package com.rita.ecommerceplatform.dto.response

import com.rita.ecommerceplatform.model.ProductImage

/** One product image as the public sees it: where to load it, its description, and whether it is the main one. */
data class ProductImageResponse(
    val url: String,
    val altText: String?,
    val primary: Boolean,
) {
    companion object {
        fun from(image: ProductImage) = ProductImageResponse(url = image.url, altText = image.altText, primary = image.isPrimary)
    }
}
