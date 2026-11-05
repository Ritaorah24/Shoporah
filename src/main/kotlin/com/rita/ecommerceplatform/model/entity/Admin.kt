package com.rita.ecommerceplatform.model.entity

import java.time.LocalDateTime

/** One row of public.ec_admin, plus its role name joined from ec_role. */
data class Admin(
    var adminId: Int? = null,
    var adminRoleId: Int? = null,
    var roleName: String? = null,
    var adminFirstName: String? = null,
    var adminLastName: String? = null,
    var adminEmail: String? = null,
    var adminPhoneNumber: String? = null,
    var adminStatus: AdminStatus? = null,
    var adminCreatedAt: LocalDateTime? = null,
    var adminUpdatedAt: LocalDateTime? = null,
)

/** Allowed values of ec_admin.adminstatus (ck__ec_admin__status). */
enum class AdminStatus { ACTIVE, INACTIVE, SUSPENDED, DELETED }
