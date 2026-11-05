package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.request.UpdateProductImageRequest
import com.rita.ecommerceplatform.model.response.AdminProductImageResponse
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.exception.PayloadTooLargeException
import com.rita.ecommerceplatform.model.entity.ProductImage
import com.rita.ecommerceplatform.model.entity.ProductStatus
import com.rita.ecommerceplatform.repository.database.ProductImageRepository
import com.rita.ecommerceplatform.repository.database.ProductRepository
import com.rita.ecommerceplatform.repository.api.ImageStorage
import com.rita.ecommerceplatform.repository.api.StoredImage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile

/**
 * Product images (checkpoint 2.2, Step 8). The file goes to the image storage outside any transaction (it can take
 * seconds, like a Paystack call); the row is then saved in a short transaction with the product row locked, so the
 * limit of MAX_IMAGES and the single primary image hold even for uploads at the same time.
 */
@Service
class ProductImageService(
    private val productRepository: ProductRepository,
    private val productImageRepository: ProductImageRepository,
    private val imageStorage: ImageStorage,
    // Transactions are opened in code, like PaymentService: the storage call must run outside any transaction.
    private val transactionTemplate: TransactionTemplate,
) {

    private val log = LoggerFactory.getLogger(ProductImageService::class.java)

    /**
     * Checks the file (size, and its real type from its first bytes), stores it, and records it. The product's first
     * image becomes its primary image. If recording fails after the upload, the stored file is removed again.
     */
    fun upload(productId: Long, file: MultipartFile, altText: String?): AdminProductImageResponse {
        // Spring's multipart limit refuses bigger requests first; this check also covers any other way in.
        if (file.size > MAX_BYTES) throw PayloadTooLargeException(TOO_LARGE)
        val content = file.bytes
        if (content.isEmpty()) throw BadRequestException("The image file is empty")
        val type = ImageTypeDetector.detect(content) ?: throw BadRequestException("Only JPEG, PNG or WebP images are allowed")
        val alt = altText?.trim()?.ifEmpty { null }
        if (alt != null && alt.length > MAX_ALT_TEXT) throw BadRequestException("altText must be at most $MAX_ALT_TEXT characters")

        // Checked before uploading too, so a request that will be refused never stores a file.
        findNotDeleted(productId)
        if (productImageRepository.countByProduct(productId) >= MAX_IMAGES) throw tooManyImages()

        val stored = imageStorage.upload(content, type.contentType, "$FOLDER/$productId")
        val image = try {
            transactionTemplate.execute { save(productId, stored, alt) }!!
        } catch (e: Exception) {
            deleteQuietly(stored.storageKey)
            throw e
        }
        return AdminProductImageResponse.from(image)
    }

    /**
     * Changes an image's alt text, its place in the order, or makes it the primary image (the old primary loses the
     * flag in the same transaction). Returns the product's images as they now are.
     */
    fun update(productId: Long, imageId: Long, request: UpdateProductImageRequest): List<AdminProductImageResponse> {
        if (request.altText == null && request.sortOrder == null && request.primary == null) {
            throw BadRequestException("Nothing to update")
        }
        if (request.primary == false) throw BadRequestException("Choose another image as primary instead")
        return transactionTemplate.execute {
            // The product lock makes primary changes for one product run one at a time, so there is always one primary.
            lockNotDeleted(productId)
            val image = productImageRepository.findById(productId, imageId) ?: throw NotFoundException("Image not found")
            productImageRepository.update(
                imageId = imageId,
                altText = if (request.altText != null) request.altText.trim().ifEmpty { null } else image.productImageAltText,
                sortOrder = request.sortOrder ?: image.productImageSortOrder!!,
            )
            if (request.primary == true) productImageRepository.makePrimary(productId, imageId)
            productImageRepository.findByProduct(productId).map(AdminProductImageResponse::from)
        }!!
    }

    /**
     * Removes an image. If it was the primary one, the next by sort order becomes primary. The row goes first; the
     * stored file is removed after the commit, and a failure there is only logged (a leftover file breaks nothing).
     */
    fun delete(productId: Long, imageId: Long) {
        val image = transactionTemplate.execute {
            lockNotDeleted(productId)
            val image = productImageRepository.findById(productId, imageId) ?: throw NotFoundException("Image not found")
            productImageRepository.delete(imageId)
            if (image.productImageIsPrimary!!) productImageRepository.promoteFirstIfNoPrimary(productId)
            image
        }!!
        deleteQuietly(image.productImageStorageKey!!)
    }

    private fun save(productId: Long, stored: StoredImage, altText: String?): ProductImage {
        lockNotDeleted(productId)
        // Checked again under the lock: another upload may have added an image since the first check.
        val count = productImageRepository.countByProduct(productId)
        if (count >= MAX_IMAGES) throw tooManyImages()
        val imageId = productImageRepository.insert(
            productId = productId,
            url = stored.url,
            storageKey = stored.storageKey,
            altText = altText,
            sortOrder = productImageRepository.nextSortOrder(productId),
            isPrimary = count == 0,
        )
        return productImageRepository.findById(productId, imageId)!!
    }

    // Deleted products behave as if they do not exist (D3), images included.
    private fun findNotDeleted(productId: Long) {
        val product = productRepository.findById(productId)
        if (product == null || product.productStatus == ProductStatus.DELETED) throw NotFoundException("Product not found")
    }

    private fun lockNotDeleted(productId: Long) {
        val product = productRepository.findByIdForUpdate(productId)
        if (product == null || product.productStatus == ProductStatus.DELETED) throw NotFoundException("Product not found")
    }

    private fun deleteQuietly(storageKey: String) {
        try {
            imageStorage.delete(storageKey)
        } catch (e: Exception) {
            log.warn("Could not remove stored image {}; it is left over in the image storage", storageKey, e)
        }
    }

    private fun tooManyImages() = ConflictException("A product can have at most $MAX_IMAGES images")

    companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        const val MAX_IMAGES = 8
        const val MAX_ALT_TEXT = 200
        const val FOLDER = "shoporah/products"
        const val TOO_LARGE = "The image must be 5 MB or smaller"
    }
}
