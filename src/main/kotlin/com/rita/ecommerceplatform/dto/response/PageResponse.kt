package com.rita.ecommerceplatform.dto.response

/** One page of a listing. page is 0-based; size is the page size actually used (after the cap). */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <T> of(content: List<T>, page: Int, size: Int, totalElements: Long) = PageResponse(
            content = content,
            page = page,
            size = size,
            totalElements = totalElements,
            totalPages = ((totalElements + size - 1) / size).toInt(),
        )
    }
}
