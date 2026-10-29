package com.rita.ecommerceplatform.support

import com.rita.ecommerceplatform.exception.BadGatewayException
import com.rita.ecommerceplatform.exception.ServiceUnavailableException
import com.rita.ecommerceplatform.repository.api.ImageStorage
import com.rita.ecommerceplatform.repository.api.StoredImage
import com.rita.ecommerceplatform.repository.api.cloudinary.CloudinaryImageStorage
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Primary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Stands in for Cloudinary in every integration test (imported by IntegrationTestBase, @Primary over
 * CloudinaryImageStorage), so no test uploads anything. It keeps what was "stored" by key; a test can make it
 * unreachable (down), unconfigured (notConfigured) or fail only on delete (deleteFails). Reset them in a finally block.
 */
@TestComponent
@Primary
class FakeImageStorage : ImageStorage {

    private val stored = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    var down = false

    @Volatile
    var notConfigured = false

    @Volatile
    var deleteFails = false

    override fun upload(content: ByteArray, contentType: String, folder: String): StoredImage {
        failIfUnavailable()
        val key = "$folder/${UUID.randomUUID()}"
        stored[key] = content
        return StoredImage("https://images.example.test/$key", key)
    }

    override fun delete(storageKey: String) {
        failIfUnavailable()
        if (deleteFails) throw BadGatewayException(CloudinaryImageStorage.STORAGE_ERROR)
        stored.remove(storageKey)
    }

    /** Whether a file with this key is still "stored". */
    fun contains(storageKey: String): Boolean = stored.containsKey(storageKey)

    /** How many files are "stored" under this folder (e.g. shoporah/products/{productId}). */
    fun countIn(folder: String): Int = stored.keys.count { it.startsWith("$folder/") }

    private fun failIfUnavailable() {
        if (notConfigured) throw ServiceUnavailableException(CloudinaryImageStorage.NOT_CONFIGURED)
        if (down) throw BadGatewayException(CloudinaryImageStorage.STORAGE_ERROR)
    }
}
