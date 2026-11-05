package com.rita.ecommerceplatform.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger UI title plus the "Authorize" button. The bearer scheme is not applied globally:
 * only protected endpoints declare @SecurityRequirement, so Swagger never sends a stale
 * token to the login and register endpoints (an invalid token there would cause a 401).
 */
@Configuration
class SwaggerConfig {

    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("E-commerce Platform API")
                    .version("1.1")
                    .description(
                        "Checkpoint 1.1: customer registration and login, customer profile, " +
                            "admin login and change password. Log in, copy accessToken, then click Authorize.",
                    ),
            )
            .components(
                Components().addSecuritySchemes(
                    BEARER_AUTH,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Paste the accessToken from a login response (without the word Bearer)."),
                ),
            )

    companion object {
        /** Name used by @SecurityRequirement on protected endpoints. */
        const val BEARER_AUTH = "bearerAuth"
    }
}
