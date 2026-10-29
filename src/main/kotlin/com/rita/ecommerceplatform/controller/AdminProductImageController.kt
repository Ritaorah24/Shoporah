package com.rita.ecommerceplatform.controller

import com.rita.ecommerceplatform.config.OpenApiConfig
import com.rita.ecommerceplatform.dto.response.AdminProductImageResponse
import com.rita.ecommerceplatform.service.ProductImageService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Product images. SecurityConfig restricts every /api/admin URL to admins who have changed their password. */
@Tag(name = "Admin product images", description = "Upload and remove product images (stored on Cloudinary)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/admin/products/{productId}/images")
class AdminProductImageController(private val productImageService: ProductImageService) {

    /**
     * multipart/form-data with `file` (JPEG, PNG or WebP, at most 5 MB; the type is read from the file itself)
     * and optional `altText` (≤ 200, read by screen readers). At most 8 images per product; the first is primary.
     * 503 if image storage is not configured.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @PathVariable productId: Long,
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) altText: String?,
    ): AdminProductImageResponse = productImageService.upload(productId, file, altText)

    /** Removes the image (and its stored file). If it was primary, the next image becomes primary. */
    @DeleteMapping("/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable productId: Long, @PathVariable imageId: Long) = productImageService.delete(productId, imageId)
}
