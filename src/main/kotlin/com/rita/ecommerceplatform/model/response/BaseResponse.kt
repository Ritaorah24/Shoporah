package com.rita.ecommerceplatform.model.response

open class BaseResponse(
    open var responseCode: String = "00",
    open var responseMessage: String = "Successful",
)

data class BaseDataResponse(var responseCode: String, var responseMessage: String, var data: Any)

/** One failed validation rule. The rejected value is never included (it may be a password). */
data class ApiFieldError(val field: String, val message: String)
