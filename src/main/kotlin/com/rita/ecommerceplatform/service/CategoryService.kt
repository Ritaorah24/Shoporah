package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.RecordAlreadyExistException
import com.rita.ecommerceplatform.exception.UnableToLocateRecordException
import com.rita.ecommerceplatform.model.request.CreateCategoryRequest
import com.rita.ecommerceplatform.model.request.UpdateCategoryRequest
import com.rita.ecommerceplatform.model.request.UpdateStatusRequest
import com.rita.ecommerceplatform.model.response.AdminCategoryResponse
import com.rita.ecommerceplatform.model.response.CategoryResponse
import com.rita.ecommerceplatform.model.entity.Category
import com.rita.ecommerceplatform.model.entity.CategoryStatus
import com.rita.ecommerceplatform.repository.database.CategoryRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CategoryService(private val categoryRepository: CategoryRepository) {

    /** Public list: active categories only, sorted by name. */
    fun listActive(): List<CategoryResponse> =
        categoryRepository.findByStatuses(listOf(CategoryStatus.ACTIVE)).map(CategoryResponse::from)

    /** Admin list: active and inactive, so an inactive category can be found and reactivated. */
    fun listForAdmin(): List<AdminCategoryResponse> =
        categoryRepository.findByStatuses(listOf(CategoryStatus.ACTIVE, CategoryStatus.INACTIVE))
            .map(AdminCategoryResponse::from)

    fun getForAdmin(categoryId: Int): AdminCategoryResponse =
        AdminCategoryResponse.from(findNotDeleted(categoryId))

    @Transactional
    fun create(adminId: Int, request: CreateCategoryRequest): AdminCategoryResponse {
        // Already trimmed during JSON reading (TrimStringDeserializer); non-null after @Valid.
        val name = request.name!!
        if (categoryRepository.existsByName(name)) throw nameInUse()

        val categoryId = try {
            categoryRepository.insert(name, request.description.blankToNull(), adminId)
        } catch (e: DuplicateKeyException) {
            // Two admins creating the same name at the same moment: uq__ec_category__name stops the second.
            throw nameInUse()
        }
        return getForAdmin(categoryId)
    }

    /** Partial update: fields not sent keep their value; description "" clears it. */
    @Transactional
    fun update(categoryId: Int, request: UpdateCategoryRequest): AdminCategoryResponse {
        val existing = findNotDeleted(categoryId)
        val name = request.name ?: existing.categoryName
        if (categoryRepository.existsByName(name!!, excludingCategoryId = categoryId)) throw nameInUse()

        val description = if (request.description != null) request.description.blankToNull() else existing.categoryDescription
        try {
            categoryRepository.update(categoryId, name!!, description)
        } catch (e: DuplicateKeyException) {
            throw nameInUse()
        }
        return getForAdmin(categoryId)
    }

    /** Activate or deactivate. Deactivating hides the category and its products from the public (D2). */
    @Transactional
    fun updateStatus(categoryId: Int, request: UpdateStatusRequest): AdminCategoryResponse {
        findNotDeleted(categoryId)
        categoryRepository.updateStatus(categoryId, CategoryStatus.valueOf(request.status!!.uppercase()))
        return getForAdmin(categoryId)
    }

    /** Soft delete (final). Blocked while products still use the category (D2). */
    @Transactional
    fun delete(categoryId: Int) {
        findNotDeleted(categoryId)
        if (categoryRepository.hasProducts(categoryId)) {
            throw BadRequestException("Category still has products: move or delete them first, or deactivate the category instead")
        }
        categoryRepository.updateStatus(categoryId, CategoryStatus.DELETED)
    }

    /** Deleted categories behave as if they do not exist (D3). */
    private fun findNotDeleted(categoryId: Int): Category {
        val category = categoryRepository.findById(categoryId)
        if (category == null || category.categoryStatus == CategoryStatus.DELETED) throw UnableToLocateRecordException("Category not found")
        return category
    }

    // Covers deleted categories too: their names stay taken (unique constraint includes them).
    private fun nameInUse() = RecordAlreadyExistException("Category name is already in use")

    private fun String?.blankToNull(): String? = this?.trim()?.ifEmpty { null }
}
