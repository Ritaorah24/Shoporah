package com.rita.ecommerceplatform.integration

import com.rita.ecommerceplatform.support.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Walks every route in the app, so a route added later without protection fails here. */
class RouteProtectionTest : IntegrationTestBase() {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    // The only routes SecurityConfig leaves open.
    private fun isPublic(method: String, path: String): Boolean = when {
        path.startsWith("/{stage}/auth/") -> true
        path == "/{stage}/admin/auth/login" -> true
        path == "/webhook/paystack" && method == "POST" -> true
        method == "GET" && listOf("/{stage}/categories", "/{stage}/products", "/{stage}/delivery-methods").any { path.startsWith(it) } -> true
        path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui") -> true
        path == "/error" -> true
        else -> false
    }

    @Test
    fun `every route that is not deliberately public refuses a request with no token`() {
        val routes = handlerMapping.handlerMethods.keys.flatMap { info ->
            info.pathPatternsCondition!!.patternValues.flatMap { path ->
                info.methodsCondition.methods.map { it.name to path }
            }
        }
        assertTrue(routes.size >= 70, "expected the app's routes, found ${routes.size}")

        val checked = routes.filterNot { (method, path) -> isPublic(method, path) }
        assertTrue(checked.size >= 50, "expected the protected routes, found ${checked.size}")
        checked.forEach { (method, path) ->
            val url = path.replace("{stage}", "dev").replace(Regex("\\{[^}]+}"), "1")
            val body = mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(method), url))
                .andReturn().response.contentAsString
            assertEquals("115", jsonMapper.readTree(body)["responseCode"].asString(), "$method $path answered: $body")
        }
    }

    @Test
    fun `a request for another stage is refused`() {
        mockMvc.get("/prod/products").andExpect {
            status { isNotFound() }
            jsonPath("$.responseCode") { value("106") }
        }
    }
}
