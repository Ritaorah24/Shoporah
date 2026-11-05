package com.rita.ecommerceplatform.support

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.skyscreamer.jsonassert.JSONAssert
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A provider stand-in for the Api classes: requests are answered in order, and nothing reaches the network. */
class MockHttpServer {

    class Recorded(val method: String, val bodyAsString: String)

    class Answer(val status: Int = 200, val body: String = "", val contentType: String = "application/json", val failure: IOException? = null)

    inner class Expectation(val url: String) {
        internal val checks = mutableListOf<(Recorded) -> Unit>()
        internal var answer: Answer? = null

        fun andExpectMethod(method: String) = apply { checks += { assertEquals(method, it.method) } }

        fun andExpectJson(json: String, strict: Boolean = false) = apply { checks += { JSONAssert.assertEquals(json, it.bodyAsString, strict) } }

        fun andExpect(check: (Recorded) -> Unit) = apply { checks += check }

        fun andRespond(answer: Answer) = apply { this.answer = answer }
    }

    private val expected = ArrayDeque<Expectation>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val expectation = expected.removeFirstOrNull() ?: throw AssertionError("Unexpected request to ${request.url}")
        assertEquals(expectation.url, request.url.toString())
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        expectation.checks.forEach { it(Recorded(request.method, body)) }
        val answer = expectation.answer ?: throw AssertionError("No answer set for ${expectation.url}")
        answer.failure?.let { throw it }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.status)
            .message("mock")
            .body(answer.body.toResponseBody(answer.contentType.toMediaType()))
            .build()
    }.build()

    fun expect(url: String) = Expectation(url).also { expected.addLast(it) }

    fun reset() = expected.clear()

    fun verify() = assertTrue(expected.isEmpty(), "Requests not sent: ${expected.map { it.url }}")
}
