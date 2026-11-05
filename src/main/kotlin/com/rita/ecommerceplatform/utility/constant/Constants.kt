package com.rita.ecommerceplatform.utility.constant

object PrefixConstant {
    const val ENTITY = "PUBLIC.EC_"
}

/** Field and header names masked in the logs and the audit log. Add any new secret field here. */
object SensitiveDetails {
    val SENSITIVE_PARAMETERS = listOf(
        "password", "pin", "customerPassword", "customerConfirmPassword", "customerPasswordConfirmation", "customerOldPassword",
        "transactionPin", "currentPin", "oldPin", "newPin", "confirmPin", "pinConfirmation", "newPinConfirmation",
        "otp", "customerOtp", "oldPassword", "currentPassword", "newPassword", "confirmPassword", "confirmNewPassword",
        "answer", "answers", "newAnswers", "securityAnswer", "token", "accessToken", "refreshToken", "sessionToken",
        "sessionId", "authorization", "auth", "secret", "apiKey", "authenticationKey", "validationToken",
        "challengeToken", "resetToken", "deviceToken", "verificationToken", "pushId",
        "ledgerBalance", "availableBalance", "withdrawableBalance", "withdrawableAmount", "accountBalance",
        "balance", "balanceInNaira", "openingBalance", "maximumBalance", "maximumDeposit",
    )
    val SENSITIVE_HEADERS = listOf("authorization", "auth", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-access-token", "x-dispatch-key")
}
