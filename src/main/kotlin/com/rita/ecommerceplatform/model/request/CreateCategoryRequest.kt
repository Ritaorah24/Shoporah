package com.rita.ecommerceplatform.model.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tools.jackson.databind.annotation.JsonDeserialize

/** Body of POST /api/admin/categories. */
data class CreateCategoryRequest(
    // Trimmed before validation, so " Phones " is stored (and checked for duplicates) as "Phones".
    @JsonDeserialize(using = TrimStringDeserializer::class)
    @field:NotBlank
    @field:Size(max = 100)
    val name: String?,

    // Optional; blank is stored as no description.
    @field:Size(max = 1000)
    val description: String? = null,
)
