package com.rita.ecommerceplatform.service

import com.rita.ecommerceplatform.exception.BadRequestException
import com.rita.ecommerceplatform.exception.UnableToLocateRecordException
import com.rita.ecommerceplatform.model.response.AdminCustomerResponse
import com.rita.ecommerceplatform.model.response.AdminCustomerSummaryResponse
import com.rita.ecommerceplatform.model.response.PageResponse
import com.rita.ecommerceplatform.model.entity.CustomerStatus
import com.rita.ecommerceplatform.repository.database.CustomerRepository
import com.rita.ecommerceplatform.repository.database.OrderRepository
import org.springframework.stereotype.Service

/** Customers as admins look them up (checkpoint 2.2, Step 7). Read-only: suspending accounts comes with V4. */
@Service
class AdminCustomerService(
    private val customerRepository: CustomerRepository,
    private val orderRepository: OrderRepository,
) {

    /** Newest first; search matches the email, full name or phone number. Paging is capped like the other lists (D6). */
    fun list(page: Int, size: Int, search: String?, status: String?): PageResponse<AdminCustomerSummaryResponse> {
        val statusFilter = parseStatus(status)
        val contains = search?.trim()?.ifEmpty { null }
        val safePage = Paging.page(page)
        val safeSize = Paging.size(size)
        val customers = customerRepository.search(statusFilter, contains, safeSize, Paging.offset(safePage, safeSize))
        // One grouped query for the whole page, not one per customer.
        val orderCounts = orderRepository.countByCustomerIds(customers.map { it.customerId!! })
        return PageResponse.of(
            customers.map { AdminCustomerSummaryResponse.from(it, orderCounts[it.customerId] ?: 0) },
            safePage,
            safeSize,
            customerRepository.count(statusFilter, contains),
        )
    }

    fun get(customerId: Long): AdminCustomerResponse {
        val customer = customerRepository.findById(customerId) ?: throw UnableToLocateRecordException("Customer not found")
        return AdminCustomerResponse.from(customer, orderRepository.customerTotals(customerId))
    }

    private fun parseStatus(status: String?): CustomerStatus? {
        val value = status?.trim()?.uppercase()?.ifEmpty { null } ?: return null
        return CustomerStatus.entries.firstOrNull { it.name == value }
            ?: throw BadRequestException("status must be one of ${CustomerStatus.entries.joinToString()}")
    }
}
