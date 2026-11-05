package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/** One row of public.ec_admin, plus its role name joined from ec_role. */
data class Admin(
    val adminId: Int,
    val roleId: Int,
    val roleName: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phoneNumber: String?,
    val status: AdminStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Allowed values of ec_admin.adminstatus (ck__ec_admin__status). */
enum class AdminStatus { ACTIVE, INACTIVE, SUSPENDED, DELETED }
