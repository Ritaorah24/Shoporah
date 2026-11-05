package com.rita.ecommerceplatform.repository.api

import com.rita.ecommerceplatform.exception.ApiErrorException

/**
 * Where product images are kept (checkpoint 2.2, Step 8): an outside service that serves them to browsers by URL.
 * CloudinaryApi is the real one; tests replace it with a fake, so no test uploads anything.
 * Failing to reach the service is a 502 (ApiErrorException); not being configured is a 503.
 */
interface ImageStorage {

    /** Stores the image under `folder` with a new random name and returns where it can be seen. */
    fun upload(content: ByteArray, contentType: String, folder: String): StoredImage

    /** Removes a stored image. Removing one that is already gone is not an error. */
    fun delete(storageKey: String)
}

/** A stored image: its public HTTPS URL, and the key the storage knows it by (to delete it later). */
data class StoredImage(val url: String, val storageKey: String)
