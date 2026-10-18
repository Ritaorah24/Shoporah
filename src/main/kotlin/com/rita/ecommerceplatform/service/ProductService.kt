package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.dto.request.CreateProductRequest
import com.rita.ecommerceplatform.dto.request.UpdateProductRequest
import com.rita.ecommerceplatform.dto.request.UpdateStatusRequest
import com.rita.ecommerceplatform.dto.response.AdminProductResponse
import com.rita.ecommerceplatform.dto.response.PageResponse
import com.rita.ecommerceplatform.dto.response.ProductResponse
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.CategoryStatus
import com.rita.ecommerceplatform.model.InventoryMovementType
import com.rita.ecommerceplatform.model.Product
import com.rita.ecommerceplatform.model.ProductStatus
import com.rita.ecommerceplatform.repository.CategoryRepository
import com.rita.ecommerceplatform.repository.InventoryMovementRepository
import com.rita.ecommerceplatform.repository.ProductRepository
import com.rita.ecommerceplatform.repository.ProductSearchCriteria
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val inventoryMovementRepository: InventoryMovementRepository,
) {

    /**
     * Public list: only ACTIVE products in ACTIVE categories, newest first. A categoryId that is
     * inactive, deleted or unknown simply gives an empty page.
     */
    fun listPublic(page: Int, size: Int, search: String?, categoryId: Int?): PageResponse<ProductResponse> {
        val criteria = ProductSearchCriteria(
            statuses = listOf(ProductStatus.ACTIVE),
            activeCategoryOnly = true,
            nameContains = search?.trim()?.ifEmpty { null },
            categoryId = categoryId,
        )
        val safePage = Paging.page(page)
        val safeSize = Paging.size(size)
        val products = productRepository.search(criteria, safeSize, Paging.offset(safePage, safeSize))
        return PageResponse.of(products.map(ProductResponse::from), safePage, safeSize, productRepository.count(criteria))
    }

    /** Public details. A hidden product is 404, not 403, so the public cannot tell that it exists. */
    fun getPublic(productId: Long): ProductResponse {
        val product = productRepository.findPublicById(productId) ?: throw productNotFound()
        return ProductResponse.from(product)
    }

    /** Admin list: ACTIVE and INACTIVE (or just one, via status), in any category status. Never DELETED. */
    fun listForAdmin(page: Int, size: Int, search: String?, categoryId: Int?, status: String?): PageResponse<AdminProductResponse> {
        val statuses = when (status?.trim()?.uppercase()) {
            null, "" -> listOf(ProductStatus.ACTIVE, ProductStatus.INACTIVE)
            "ACTIVE" -> listOf(ProductStatus.ACTIVE)
            "INACTIVE" -> listOf(ProductStatus.INACTIVE)
            else -> throw BadRequestException("status must be ACTIVE or INACTIVE")
        }
        val criteria = ProductSearchCriteria(
            statuses = statuses,
            activeCategoryOnly = false,
            nameContains = search?.trim()?.ifEmpty { null },
            categoryId = categoryId,
        )
        val safePage = Paging.page(page)
        val safeSize = Paging.size(size)
        val products = productRepository.search(criteria, safeSize, Paging.offset(safePage, safeSize))
        return PageResponse.of(products.map(AdminProductResponse::from), safePage, safeSize, productRepository.count(criteria))
    }

    fun getForAdmin(productId: Long): AdminProductResponse = AdminProductResponse.from(findNotDeleted(productId))

    @Transactional
    fun create(adminId: Int, request: CreateProductRequest): AdminProductResponse {
        // Fields are non-null here: @Valid in the controller has already rejected missing ones.
        val sku = request.sku!!.uppercase()
        val price = request.price!!
        val stock = request.stockQuantity ?: 0

        requireUsableCategory(request.categoryId!!)
        checkDiscount(price, request.discountPrice)
        if (productRepository.existsBySku(sku)) throw skuInUse()

        val productId = try {
            productRepository.insert(
                categoryId = request.categoryId,
                createdByAdminId = adminId,
                name = request.name!!,
                description = request.description.blankToNull(),
                sku = sku,
                price = price,
                discountPrice = request.discountPrice,
                stockQuantity = stock,
                lowStockThreshold = request.lowStockThreshold,
            )
        } catch (e: DuplicateKeyException) {
            // Two admins using the same SKU at the same moment: uq__ec_product__sku stops the second.
            throw skuInUse()
        }

        if (stock > 0) {
            inventoryMovementRepository.insert(productId, InventoryMovementType.RESTOCK, stock, stock, adminId, "Initial stock")
        }
        return getForAdmin(productId)
    }

    /**
     * Partial update. The row is locked first (FOR UPDATE), so the stock read here cannot be changed
     * by a concurrent order before this update is saved; the logged change is then always correct.
     */
    @Transactional
    fun update(adminId: Int, productId: Long, request: UpdateProductRequest): AdminProductResponse {
        val existing = productRepository.findByIdForUpdate(productId)
        if (existing == null || existing.status == ProductStatus.DELETED) throw productNotFound()

        val categoryId = request.categoryId ?: existing.categoryId
        if (request.categoryId != null) requireUsableCategory(categoryId)

        val sku = request.sku?.uppercase() ?: existing.sku
        if (productRepository.existsBySku(sku, excludingProductId = productId)) throw skuInUse()

        // Checked on the merged values: a new price must still be above the existing discount.
        val price = request.price ?: existing.price
        val discountPrice = when {
            request.clearDiscountPrice == true -> null
            request.discountPrice != null -> request.discountPrice
            else -> existing.discountPrice
        }
        checkDiscount(price, discountPrice)

        val stock = request.stockQuantity ?: existing.stockQuantity
        try {
            productRepository.update(
                productId = productId,
                categoryId = categoryId,
                name = request.name ?: existing.name,
                description = if (request.description != null) request.description.blankToNull() else existing.description,
                sku = sku,
                price = price,
                discountPrice = discountPrice,
                stockQuantity = stock,
                lowStockThreshold = request.lowStockThreshold ?: existing.lowStockThreshold,
            )
        } catch (e: DuplicateKeyException) {
            throw skuInUse()
        }

        val change = stock - existing.stockQuantity
        if (change != 0) {
            val type = if (change > 0) InventoryMovementType.RESTOCK else InventoryMovementType.ADJUSTMENT
            inventoryMovementRepository.insert(productId, type, change, stock, adminId, "Edited by admin")
        }
        return getForAdmin(productId)
    }

    /** Activate or deactivate. Inactive products are hidden from the public listing. */
    @Transactional
    fun updateStatus(productId: Long, request: UpdateStatusRequest): AdminProductResponse {
        findNotDeleted(productId)
        productRepository.updateStatus(productId, ProductStatus.valueOf(request.status!!.uppercase()))
        return getForAdmin(productId)
    }

    /** Soft delete (final, D3): the row stays so future order items can still reference it. */
    @Transactional
    fun delete(productId: Long) {
        findNotDeleted(productId)
        productRepository.updateStatus(productId, ProductStatus.DELETED)
    }

    /** Deleted products behave as if they do not exist (D3). */
    private fun findNotDeleted(productId: Long): Product {
        val product = productRepository.findById(productId)
        if (product == null || product.status == ProductStatus.DELETED) throw productNotFound()
        return product
    }

    /**
     * The category in the request body must exist and not be deleted. 400 rather than 404:
     * the URL is fine, the data refers to something missing. Inactive categories are allowed,
     * so products can be prepared before a category goes live.
     */
    private fun requireUsableCategory(categoryId: Int) {
        val category = categoryRepository.findById(categoryId)
        if (category == null || category.status == CategoryStatus.DELETED) {
            throw BadRequestException("Category $categoryId does not exist")
        }
    }

    // Same rule as ck__ec_product__discountprice, checked first so the client gets a clear message.
    private fun checkDiscount(price: BigDecimal, discountPrice: BigDecimal?) {
        if (discountPrice != null && discountPrice >= price) {
            throw BadRequestException("discountPrice must be less than price")
        }
    }

    private fun productNotFound() = NotFoundException("Product not found")

    // Covers deleted products too: their SKUs stay taken (unique constraint includes them).
    private fun skuInUse() = ConflictException("SKU is already in use")

    private fun String?.blankToNull(): String? = this?.trim()?.ifEmpty { null }
}
