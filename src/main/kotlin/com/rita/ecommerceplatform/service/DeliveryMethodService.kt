package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.model.request.CreateDeliveryMethodRequest
import com.rita.ecommerceplatform.model.request.UpdateDeliveryMethodRequest
import com.rita.ecommerceplatform.model.request.UpdateStatusRequest
import com.rita.ecommerceplatform.model.response.AdminDeliveryMethodResponse
import com.rita.ecommerceplatform.model.response.DeliveryMethodResponse
import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.ConflictException
import com.rita.ecommerceplatform.exception.NotFoundException
import com.rita.ecommerceplatform.model.entity.DeliveryMethod
import com.rita.ecommerceplatform.model.entity.DeliveryMethodStatus
import com.rita.ecommerceplatform.repository.database.DeliveryMethodRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Delivery methods and their fees (checkpoint 2.2). There is no delete: orders reference their method
 * (ON DELETE RESTRICT), so a method that is no longer offered is deactivated instead.
 */
@Service
class DeliveryMethodService(private val deliveryMethodRepository: DeliveryMethodRepository) {

    /** Public list: active methods, cheapest first. */
    fun listActive(): List<DeliveryMethodResponse> =
        deliveryMethodRepository.findAllActive().map(DeliveryMethodResponse::from)

    fun listForAdmin(): List<AdminDeliveryMethodResponse> =
        deliveryMethodRepository.findAll().map(AdminDeliveryMethodResponse::from)

    fun getForAdmin(deliveryMethodId: Int): AdminDeliveryMethodResponse =
        AdminDeliveryMethodResponse.from(find(deliveryMethodId))

    @Transactional
    fun create(request: CreateDeliveryMethodRequest): AdminDeliveryMethodResponse {
        // Already trimmed during JSON reading (TrimStringDeserializer); non-null after @Valid.
        val code = request.code!!.uppercase()
        checkDays(request.estimatedMinDays, request.estimatedMaxDays)
        if (deliveryMethodRepository.existsByCode(code)) throw codeInUse()

        val deliveryMethodId = try {
            deliveryMethodRepository.insert(
                code = code,
                name = request.name!!,
                description = request.description.blankToNull(),
                fee = request.fee!!,
                estimatedMinDays = request.estimatedMinDays,
                estimatedMaxDays = request.estimatedMaxDays,
            )
        } catch (e: DuplicateKeyException) {
            // Two admins creating the same code at the same moment: uq__ec_deliverymethod__code stops the second.
            throw codeInUse()
        }
        return getForAdmin(deliveryMethodId)
    }

    /** Partial update: fields not sent keep their value; description "" clears it. Only new orders use a new fee. */
    @Transactional
    fun update(deliveryMethodId: Int, request: UpdateDeliveryMethodRequest): AdminDeliveryMethodResponse {
        val existing = find(deliveryMethodId)
        val minDays = request.estimatedMinDays ?: existing.deliveryMethodEstimatedMinDays
        val maxDays = request.estimatedMaxDays ?: existing.deliveryMethodEstimatedMaxDays
        // Checked on the merged values, so sending only one of the two is compared with the stored other.
        checkDays(minDays, maxDays)

        deliveryMethodRepository.update(
            deliveryMethodId = deliveryMethodId,
            name = request.name ?: existing.deliveryMethodName!!,
            description = if (request.description != null) request.description.blankToNull() else existing.deliveryMethodDescription,
            fee = request.fee ?: existing.deliveryMethodFee!!,
            estimatedMinDays = minDays,
            estimatedMaxDays = maxDays,
        )
        return getForAdmin(deliveryMethodId)
    }

    /** Activate or deactivate. Inactive methods disappear from the public list and checkout refuses them. */
    @Transactional
    fun updateStatus(deliveryMethodId: Int, request: UpdateStatusRequest): AdminDeliveryMethodResponse {
        val existing = find(deliveryMethodId)
        val status = DeliveryMethodStatus.valueOf(request.status!!.uppercase())
        // Checkout falls back to STANDARD when no method is named, so it must always be available.
        if (status == DeliveryMethodStatus.INACTIVE && existing.deliveryMethodCode.equals(OrderService.DEFAULT_DELIVERY_METHOD, ignoreCase = true)) {
            throw ConflictException("${OrderService.DEFAULT_DELIVERY_METHOD} is the default delivery method and cannot be deactivated")
        }
        deliveryMethodRepository.updateStatus(deliveryMethodId, status)
        return getForAdmin(deliveryMethodId)
    }

    private fun find(deliveryMethodId: Int): DeliveryMethod =
        deliveryMethodRepository.findById(deliveryMethodId) ?: throw NotFoundException("Delivery method not found")

    private fun checkDays(minDays: Int?, maxDays: Int?) {
        if (minDays != null && maxDays != null && maxDays < minDays) {
            throw BadRequestException("estimatedMaxDays must not be less than estimatedMinDays")
        }
    }

    private fun codeInUse() = ConflictException("Delivery method code is already in use")

    private fun String?.blankToNull(): String? = this?.trim()?.ifEmpty { null }
}
