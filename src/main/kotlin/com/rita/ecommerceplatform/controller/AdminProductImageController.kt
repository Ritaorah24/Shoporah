package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.model.response.BaseDataResponse
import com.rita.ecommerceplatform.model.response.BaseResponse
import com.rita.ecommerceplatform.config.SwaggerConfig
import com.rita.ecommerceplatform.model.request.UpdateProductImageRequest
import com.rita.ecommerceplatform.service.ProductImageService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Product images. SecurityConfig restricts every /{stage}/admin URL to admins who have changed their password. */
@Tag(name = "Admin product images", description = "Upload, order and remove product images (stored on Cloudinary)")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
@RestController
@RequestMapping("/{stage}/admin/products/{productId}/images")
class AdminProductImageController(private val productImageService: ProductImageService) {

    /**
     * multipart/form-data with `file` (JPEG, PNG or WebP, at most 5 MB; the type is read from the file itself)
     * and optional `altText` (≤ 200, read by screen readers). At most 8 images per product; the first is primary.
     * code 22 if image storage is not configured.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @PathVariable productId: Long,
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) altText: String?,
        @PathVariable stage: String,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", productImageService.upload(productId, file, altText))
    }

    /**
     * Any of altText (≤ 200; "" clears it), sortOrder (0-999, lower first) and primary: true (the old primary loses it).
     * Returns the product's images in their new order. primary: false is code 106: make another image primary instead.
     */
    @PatchMapping("/{imageId}")
    fun update(
        @PathVariable productId: Long,
        @PathVariable imageId: Long,
        @Valid @RequestBody request: UpdateProductImageRequest,
        @PathVariable stage: String,
    ): BaseDataResponse {
        return BaseDataResponse("00", "Successful", productImageService.update(productId, imageId, request))
    }

    /** Removes the image (and its stored file). If it was primary, the next image becomes primary. */
    @DeleteMapping("/{imageId}")
    fun delete(@PathVariable productId: Long, @PathVariable imageId: Long, @PathVariable stage: String): BaseResponse {
        productImageService.delete(productId, imageId)
        return BaseResponse()
    }
}
